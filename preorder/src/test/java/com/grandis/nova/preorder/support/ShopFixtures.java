package com.grandis.nova.preorder.support;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 테스트 데이터. 다른 모듈 소유 행(회원 · 카테고리 · 상품 · 옵션)까지 SQL 로 바로 넣는다.
 *
 * 매번 새 행을 만들고 지우지 않는다. id 와 유일 칸은 UUID 로 채워 테스트끼리 겹치지 않으므로
 * 커밋하는 동시성 테스트와 롤백하는 테스트가 같은 컨테이너를 순서 상관없이 쓸 수 있다.
 */
public class ShopFixtures {

    public static final long FIRST_BATCH_LAST_POSITION = 100;

    private final JdbcTemplate jdbcTemplate;

    public ShopFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID customer() {
        return insert("""
                INSERT INTO customers (id, kakao_id, display_name, created_at, updated_at)
                VALUES (?, ?, '테스트 회원', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
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
        UUID productId = product("PREORDER", "ACTIVE");
        UUID optionId = option(productId, "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO preorder_campaigns (product_id, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UuidBinary.toBytes(productId), utc(opensAt), utc(closesAt));
        UUID firstBatchId = batch(productId, 1, 1, FIRST_BATCH_LAST_POSITION);
        UUID lastBatchId = batch(productId, 2, FIRST_BATCH_LAST_POSITION + 1, null);
        return new PreorderProduct(productId, optionId, firstBatchId, lastBatchId);
    }

    public UUID product(String saleMode, String status) {
        UUID categoryId = insert("""
                INSERT INTO categories (id, name, created_at, updated_at)
                VALUES (?, '스마트폰', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        return insert("""
                INSERT INTO products (id, category_id, sale_mode, title, status, created_at, updated_at)
                VALUES (?, ?, ?, 'Nova 1', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UuidBinary.toBytes(categoryId), saleMode, status);
    }

    public UUID option(UUID productId, String status) {
        return insert("""
                INSERT INTO product_options (id, product_id, sku, title, price, status, created_at, updated_at)
                VALUES (?, ?, ?, '블랙 / 256GB', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UuidBinary.toBytes(productId), unique(), new BigDecimal("1250000"), status);
    }

    private UUID batch(UUID productId, int batchNumber, long positionFrom, Long positionTo) {
        LocalDate shipStart = LocalDate.of(2026, 11, 1).plusMonths(batchNumber - 1);
        return insert("""
                INSERT INTO shipment_batches (id, product_id, batch_number, position_from, position_to,
                                              estimated_ship_start, estimated_ship_end, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, UuidBinary.toBytes(productId), batchNumber, positionFrom, positionTo, shipStart,
                shipStart.plusDays(6));
    }

    /** DB 는 UTC 벽시계 시각을 담는다. Timestamp 로 넘기면 JVM 시간대로 바뀌어 들어간다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** worker 가 남기는 시도 기록. preorder 는 관리자 화면에서 읽기만 한다. */
    public void syncAttempt(UUID syncJobId, int attemptNumber, String result, Integer httpStatus, String errorCode) {
        jdbcTemplate.update("""
                INSERT INTO preorder_sync_attempts (sync_job_id, attempt_number, actor, result, http_status,
                                                    error_code, started_at, finished_at)
                VALUES (?, ?, 'SYSTEM', ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UuidBinary.toBytes(syncJobId), attemptNumber, result, httpStatus, errorCode);
    }

    /**
     * worker 가 외부 호출에 성공해 작업을 SUCCEEDED 로 바꾼 것처럼 만든다. preorder 코드에는 이 전이가 없다.
     *
     * @return 그 작업의 id
     */
    public UUID workerSucceeds(UUID preorderId, String jobType) {
        UUID jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = ?",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)), UuidBinary.toBytes(preorderId), jobType);
        jdbcTemplate.update("UPDATE preorder_sync_jobs SET status = 'SUCCEEDED' WHERE id = ?",
                (Object) UuidBinary.toBytes(jobId));
        return jobId;
    }

    /**
     * order 가 PREORDER_ORDER_SETTLED 에 돌려줄 취소 시도 순번. 마지막 PREORDER_CANCEL_REQUESTED 에 실린 값을 읽는다.
     */
    public Long cancelSequence(UUID preorderId) {
        return jdbcTemplate.queryForObject("""
                SELECT JSON_EXTRACT(payload, '$.cancelSequence') FROM preorder_outbox_events
                 WHERE event_type = 'PREORDER_CANCEL_REQUESTED' AND aggregate_id = ?
                 ORDER BY id DESC LIMIT 1
                """, Long.class, (Object) UuidBinary.toBytes(preorderId));
    }

    /** worker 가 등록을 포기한 것처럼 예약의 REGISTER 작업을 DEAD_LETTER 로 만든다. @return 그 작업의 id */
    public UUID deadLetter(UUID preorderId) {
        UUID jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'REGISTER'",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)), (Object) UuidBinary.toBytes(preorderId));
        jdbcTemplate.update("""
                UPDATE preorder_sync_jobs SET status = 'DEAD_LETTER', dead_lettered_at = UTC_TIMESTAMP(6) WHERE id = ?
                """, (Object) UuidBinary.toBytes(jobId));
        return jobId;
    }

    /** 확인용 건수 조회. */
    public int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    /** 회차의 다음 순번 카운터. */
    public long nextQueuePosition(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT next_queue_position FROM preorder_campaigns WHERE product_id = ?",
                Long.class, (Object) UuidBinary.toBytes(productId));
    }

    public static String unique() {
        return UUID.randomUUID().toString();
    }

    /** 첫 칸(id)에 새 UUID 를 넣고 나머지 칸을 채운다. @return 넣은 id */
    private UUID insert(String sql, Object... args) {
        UUID id = UUID.randomUUID();
        Object[] values = new Object[args.length + 1];
        values[0] = UuidBinary.toBytes(id);
        System.arraycopy(args, 0, values, 1, args.length);
        jdbcTemplate.update(sql, values);
        return id;
    }

    public record PreorderProduct(UUID productId, UUID optionId, UUID firstBatchId, UUID lastBatchId) {
    }
}
