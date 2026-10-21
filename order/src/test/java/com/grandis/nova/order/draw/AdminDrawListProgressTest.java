package com.grandis.nova.order.draw;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.support.GeneralLog;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.TestAuth;
import com.grandis.nova.order.web.PageSizes;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 회차 목록의 진행 요약 — 회차별 진행 API 와 같은 값, 응모 없는 회차는 0, 단계(시작 전 · 응모 중 · 추첨 중 · 멈춤 · 발표), 한 쪽의 진행은 문장 하나로 센다. */
@OrderIntegrationTest
@AutoConfigureMockMvc
class AdminDrawListProgressTest {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderLedger ledger;
    @Autowired TransactionTemplate transactionTemplate;

    OrderFixtures fixtures;
    OrderFixtures.StockProduct gift;

    /** 이 시험이 만든 회차. 끝나면 응모와 함께 지운다 — 응모 중 · 마감 미반영 회차가 남으면 같은 DB 의 목록 총계 · 마감 고르기를 흔든다. */
    private final List<UUID> draws = new ArrayList<>();

    @AfterEach
    void removeDraws() {
        for (UUID draw : draws) {
            jdbcTemplate.update("DELETE FROM draw_entries WHERE campaign_id = ?", (Object) bytes(draw));
            jdbcTemplate.update("DELETE FROM draw_campaigns WHERE id = ?", (Object) bytes(draw));
        }
    }

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        gift = fixtures.inStockProduct(1);
    }

    @Test
    void eachRowCarriesProgressSummaryMatchingProgressApi() throws Exception {
        UUID busy = draw();
        entry(busy, "PAID", wonOrder());
        entry(busy, "PAID", null);
        entry(busy, "AUTHORIZING", null);
        entry(busy, "AWAITING_PAYMENT", null);
        entry(busy, "AWAITING_PAYMENT", null);
        jdbcTemplate.update("UPDATE draw_campaigns SET draw_requested_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) bytes(busy));
        UUID empty = draw();
        UUID settled = draw();
        entry(settled, "PAID", wonOrder());
        jdbcTemplate.update("UPDATE draw_campaigns SET draw_requested_at = UTC_TIMESTAMP(6), settled_at = UTC_TIMESTAMP(6) WHERE id = ?",
                (Object) bytes(settled));

        String body = adminList();

        for (UUID draw : List.of(busy, empty, settled)) {
            Map<String, Object> progress = progressApi(draw);
            Map<String, Object> row = row(body, draw);
            assertThat(row).as(draw.toString())
                    .containsEntry("entryCount", (Integer) progress.get("paidEntries") + (Integer) progress.get("authorizingEntries"))
                    .containsEntry("winners", progress.get("winners"))
                    .containsEntry("settledAt", progress.get("settledAt"))
                    .doesNotContainKey("progress");
        }
        assertThat(row(body, busy)).containsEntry("entryCount", 3).containsEntry("winners", 1).containsEntry("stage", "ENTRY_OPEN")
                .containsEntry("title", "드로우");
        assertThat(row(body, empty)).containsEntry("entryCount", 0).containsEntry("winners", 0).containsEntry("stage", "ENTRY_OPEN");
        assertThat(row(body, settled)).containsEntry("stage", "WINNERS_ANNOUNCED").extracting(r -> r.get("settledAt")).isNotNull();
    }

    @Test
    void stageFollowsScheduleClosingAndStall() throws Exception {
        Instant now = Instant.now();
        UUID scheduled = draw(now.plus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)));
        UUID drawing = draw(now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(10)));
        UUID stuck = draw(now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(2)));
        entry(stuck, "AUTHORIZING", null);

        String body = adminList();

        assertThat(row(body, scheduled)).containsEntry("stage", "SCHEDULED").containsEntry("phase", "SCHEDULED");
        assertThat(row(body, drawing)).containsEntry("stage", "DRAWING").containsEntry("phase", "CLOSED");
        assertThat(row(body, stuck)).containsEntry("stage", "STUCK").containsEntry("entryCount", 1);
    }

    @Test
    void progressOfPageIsOneStatement() throws Exception {
        UUID first = draw();
        entry(first, "PAID", null);
        draw();

        // 진행 문장을 보낸 연결의 문장만 받는다 — 같은 서버의 다른 작업이 draw_entries 를 읽어도 섞이지 않게 진행 문장 모양으로 고른다
        List<String> statements = GeneralLog.statements("%LEFT JOIN draw_entries%IN (%", this::adminList);

        List<String> progress = statements.stream().filter(sql -> sql.contains("draw_entries")).toList();
        assertThat(progress).as("쪽당 진행 문장 하나").singleElement();
        // 목록과 진행이 한 읽기 트랜잭션 — 커밋은 한 번이고, 두 문장 모두 그 커밋 앞에 있다(따로 열었으면 커밋이 둘이거나 진행이 커밋 뒤다)
        List<String> commits = statements.stream().filter(sql -> sql.equalsIgnoreCase("COMMIT")).toList();
        assertThat(commits).as("커밋 한 번").hasSize(1);
        int commit = statements.indexOf(commits.getFirst());
        int list = statements.indexOf(statements.stream()
                .filter(sql -> sql.startsWith("select") && sql.contains("from draw_campaigns") && !sql.contains("draw_entries"))
                .findFirst().orElseThrow());
        assertThat(list).as("목록 문장이 커밋 앞").isLessThan(commit);
        assertThat(statements.indexOf(progress.getFirst())).as("진행 문장이 목록 뒤 · 커밋 앞").isBetween(list + 1, commit - 1);
        // 계획은 행 수를 보고 정한다 — 공유 DB 의 응모가 몇 건뿐이면 한 쪽 회차가 응모 대부분을 덮어 표 전체를 읽는 편이 싸다. 운영처럼 다른 회차의
        // 응모가 많은 모양을 만든다: 받은 진행 문장의 IN 목록에 없는 회차에 응모를 심고 통계를 다시 잰 뒤 그 문장을 본다
        UUID crowded = draw();
        String salt = crowded.toString();
        try {
            jdbcTemplate.update("""
                    INSERT /*+ SET_VAR(cte_max_recursion_depth = 3001) */ INTO customers (id, kakao_id, display_name, created_at, updated_at)
                    WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 3000)
                    SELECT UNHEX(MD5(CONCAT(?, 'c', n))), CONCAT(?, '-', n), '응모자', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM seq""", salt, salt);
            jdbcTemplate.update("""
                    INSERT /*+ SET_VAR(cte_max_recursion_depth = 3001) */ INTO draw_entries (id, campaign_id, customer_id, status, ship_to_name,
                                                                                            ship_to_phone, ship_to_postal_code, ship_to_line1, created_at, updated_at)
                    WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 3000)
                    SELECT UNHEX(MD5(CONCAT(?, 'e', n))), ?, UNHEX(MD5(CONCAT(?, 'c', n))), 'PAID', '응모자', '010-0000-0000', '04524', '서울시 중구',
                           UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                      FROM seq""", salt, bytes(crowded), salt);
            jdbcTemplate.execute("ANALYZE TABLE draw_campaigns, draw_entries");
            // 응모는 회차 id 로 시작하는 색인으로 그 회차 것만 읽는다. 회차 표는 IN 목록이 표 대부분이면 전부 읽기도 한다(회차는 수가 적다)
            Map<String, Object> entries = jdbcTemplate.queryForList("EXPLAIN " + progress.getFirst()).stream()
                    .filter(row -> "e".equals(row.get("table"))).findFirst().orElseThrow();
            assertThat(entries.get("key")).as(entries.toString()).isIn("ix_draw_entry_campaign_status", "uq_draw_entry_customer");
            assertThat(entries.get("type")).isNotEqualTo("ALL");
        } finally {
            // 회차는 removeDraws 가 지운다. 회원은 응모가 가리키므로 응모부터 지운다
            jdbcTemplate.update("DELETE FROM draw_entries WHERE campaign_id = ?", (Object) bytes(crowded));
            jdbcTemplate.update("DELETE FROM customers WHERE kakao_id LIKE ?", salt + "-%");
        }
    }

    @Test
    void userTokenIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/draws").with(TestAuth.customer(fixtures.customer())))
                .andExpect(status().isForbidden());
    }

    private String adminList() throws Exception {
        return mockMvc.perform(get("/api/v1/admin/draws").param("size", "" + PageSizes.MAX).with(TestAuth.admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static Map<String, Object> row(String body, UUID draw) {
        List<Map<String, Object>> found = JsonPath.read(body, "$.data.items[?(@.drawId == '%s')]".formatted(draw));
        assertThat(found).as("목록에 회차 " + draw).hasSize(1);
        return found.getFirst();
    }

    private Map<String, Object> progressApi(UUID draw) throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/draws/{id}/progress", draw).with(TestAuth.admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data");
    }

    /** 응모 중인 회차. 목록 맨 앞에 오도록 지금 만든다. */
    private UUID draw() {
        Instant now = Instant.now();
        return draw(now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)));
    }

    /** 그 기간의 회차. 마감 처리는 시험 중에 돌지 않는다(nova.draw.close-interval=1h) — 지난 회차도 반영되지 않은 채 남는다. */
    private UUID draw(Instant opensAt, Instant closesAt) {
        UUID id = UUID.randomUUID();
        draws.add(id);
        jdbcTemplate.update("""
                INSERT INTO draw_campaigns (id, idempotency_key, product_id, option_id, title, product_title_snapshot, model_name_snapshot,
                                            option_title_snapshot, image_url_snapshot, entry_fee, winner_count, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, '드로우', '증정품', 'A3104', '블랙', 'https://img/gift-0.jpg', 100, 1, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(id), id.toString(), bytes(gift.productId()), bytes(gift.optionIds().getFirst()), UTC.format(opensAt), UTC.format(closesAt));
        return id;
    }

    private void entry(UUID draw, String status, UUID wonOrder) {
        jdbcTemplate.update("""
                INSERT INTO draw_entries (id, campaign_id, customer_id, status, authorizing_provider_order_id, won_order_id, ship_to_name,
                                          ship_to_phone, ship_to_postal_code, ship_to_line1, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '응모자', '010-0000-0000', '04524', '서울시 중구', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(UUID.randomUUID()), bytes(draw), bytes(fixtures.customer()), status,
                "AUTHORIZING".equals(status) ? UUID.randomUUID().toString() : null, wonOrder == null ? null : bytes(wonOrder));
    }

    /** 당첨 응모가 가리킬 주문. 표의 외래 키 · 유일 키만 맞춘다(당첨 주문의 모양은 이 시험이 보지 않는다). */
    private UUID wonOrder() {
        return new PlacedOrders(ledger, transactionTemplate, fixtures).place(fixtures.customer()).id();
    }
}
