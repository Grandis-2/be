package com.grandis.nova.preorder.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 테스트 데이터. 다른 모듈 소유 행(회원 · 카테고리 · 상품 · 옵션)까지 SQL 로 바로 넣는다.
 *
 * 매번 새 행을 만들고 지우지 않는다. 유일 칸은 UUID 로 채워 테스트끼리 겹치지 않으므로
 * 커밋하는 동시성 테스트와 롤백하는 테스트가 같은 컨테이너를 순서 상관없이 쓸 수 있다.
 */
public class ShopFixtures {

    public static final long FIRST_BATCH_LAST_POSITION = 100;

    private final JdbcTemplate jdbcTemplate;

    public ShopFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long customer() {
        return insert("""
                INSERT INTO customers (kakao_id, display_name, created_at, updated_at)
                VALUES (?, '테스트 회원', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, unique());
    }

    /**
     * 지금 접수 중인 사전예약 상품. 옵션 하나, 차수 둘(1~100, 101~).
     */
    public PreorderProduct openPreorderProduct() {
        Instant now = Instant.now();
        return preorderProduct(now.minusSeconds(3600), now.plusSeconds(3600));
    }

    public PreorderProduct preorderProduct(Instant opensAt, Instant closesAt) {
        Long productId = product("PREORDER", "ACTIVE");
        Long optionId = option(productId, "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO preorder_campaigns (product_id, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, utc(opensAt), utc(closesAt));
        Long firstBatchId = batch(productId, 1, 1, FIRST_BATCH_LAST_POSITION);
        Long lastBatchId = batch(productId, 2, FIRST_BATCH_LAST_POSITION + 1, null);
        return new PreorderProduct(productId, optionId, firstBatchId, lastBatchId);
    }

    public Long product(String saleMode, String status) {
        Long categoryId = insert("""
                INSERT INTO categories (code, name, created_at, updated_at)
                VALUES (?, '스마트폰', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, unique());
        return insert("""
                INSERT INTO products (category_id, sale_mode, title, status, created_at, updated_at)
                VALUES (?, ?, 'Nova 1', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, categoryId, saleMode, status);
    }

    public Long option(Long productId, String status) {
        return insert("""
                INSERT INTO product_options (product_id, sku, title, price, status, created_at, updated_at)
                VALUES (?, ?, '블랙 / 256GB', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, unique(), new BigDecimal("1250000"), status);
    }

    private Long batch(Long productId, int batchNumber, long positionFrom, Long positionTo) {
        LocalDate shipStart = LocalDate.of(2026, 11, 1).plusMonths(batchNumber - 1);
        return insert("""
                INSERT INTO shipment_batches (product_id, batch_number, position_from, position_to,
                                              estimated_ship_start, estimated_ship_end, created_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, productId, batchNumber, positionFrom, positionTo, shipStart, shipStart.plusDays(6));
    }

    /** DB 는 UTC 벽시계 시각을 담는다. Timestamp 로 넘기면 JVM 시간대로 바뀌어 들어간다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** worker 가 남기는 시도 기록. preorder 는 관리자 화면에서 읽기만 한다. */
    public void syncAttempt(Long syncJobId, int attemptNumber, String result, Integer httpStatus, String errorCode) {
        jdbcTemplate.update("""
                INSERT INTO preorder_sync_attempts (sync_job_id, attempt_number, actor, result, http_status,
                                                    error_code, started_at, finished_at)
                VALUES (?, ?, 'SYSTEM', ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, syncJobId, attemptNumber, result, httpStatus, errorCode);
    }

    /**
     * worker 가 외부 호출에 성공해 작업을 SUCCEEDED 로 바꾼 것처럼 만든다. preorder 코드에는 이 전이가 없다.
     *
     * @return 그 작업의 id
     */
    public Long workerSucceeds(Long preorderId, String jobType) {
        Long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = ?", Long.class,
                preorderId, jobType);
        jdbcTemplate.update("UPDATE preorder_sync_jobs SET status = 'SUCCEEDED' WHERE id = ?", jobId);
        return jobId;
    }

    /**
     * order 가 PREORDER_ORDER_SETTLED 에 돌려줄 취소 시도 순번. 마지막 PREORDER_CANCEL_REQUESTED 에 실린 값을 읽는다.
     */
    public Long cancelSequence(Long preorderId) {
        return jdbcTemplate.queryForObject("""
                SELECT JSON_EXTRACT(payload, '$.cancelSequence') FROM outbox_events
                 WHERE event_type = 'PREORDER_CANCEL_REQUESTED' AND aggregate_id = ?
                 ORDER BY id DESC LIMIT 1
                """, Long.class, preorderId);
    }

    /** worker 가 등록을 포기한 것처럼 예약의 REGISTER 작업을 DEAD_LETTER 로 만든다. @return 그 작업의 id */
    public Long deadLetter(Long preorderId) {
        Long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'REGISTER'", Long.class,
                preorderId);
        jdbcTemplate.update("""
                UPDATE preorder_sync_jobs SET status = 'DEAD_LETTER', dead_lettered_at = UTC_TIMESTAMP(6) WHERE id = ?
                """, jobId);
        return jobId;
    }

    /** 확인용 건수 조회. */
    public int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    /** 회차의 다음 순번 카운터. */
    public long nextQueuePosition(Long productId) {
        return jdbcTemplate.queryForObject("SELECT next_queue_position FROM preorder_campaigns WHERE product_id = ?",
                Long.class, productId);
    }

    public static String unique() {
        return UUID.randomUUID().toString();
    }

    private Long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keyHolder);
        return keyHolder.getKeyAs(Number.class).longValue();
    }

    public record PreorderProduct(Long productId, Long optionId, Long firstBatchId, Long lastBatchId) {
    }
}
