package com.grandis.nova.order.draw;

import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 응모 목록(GET /api/v1/draws/entries/me). 회차 · 응모는 표에 직접 심고, 당첨은 결과 반영(전원 당첨)을 실제로 돌려 만든다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class MyDrawEntriesApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DrawSettlement settlement;

    OrderFixtures fixtures;
    UUID customerId;
    Instant base;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        base = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofDays(10));
    }

    @Test
    @DisplayName("전체 탭은 내 응모 전부를 응모 최신순으로 — 결제 대기 · 결제 확인 중(마감 뒤) · 미결제 마감 · 결과 대기 · 낙첨 · 당첨 배지, 건수, 남의 응모는 빠진다")
    void allTabListsEveryEntryNewestFirstWithState() throws Exception {
        Entries e = sixEntries();
        UUID other = fixtures.customer();
        entry(e.awaiting.draw, other, "AWAITING_PAYMENT", null, base.plus(Duration.ofHours(9)));

        JsonNode data = data(list(customerId, "").andExpect(status().isOk()));

        List<String> ids = new ArrayList<>();
        List<String> states = new ArrayList<>();
        data.get("items").forEach(item -> {
            ids.add(item.get("entryId").asString());
            states.add(item.get("state").asString());
        });
        assertThat(ids).as("응모 최신순").containsExactly(e.won.entry.toString(), e.lost.entry.toString(), e.result.entry.toString(),
                e.closedUnpaid.entry.toString(), e.authorizing.entry.toString(), e.awaiting.entry.toString());
        assertThat(states).containsExactly("WON", "LOST", "AWAITING_RESULT", "CLOSED_UNPAID", "PAYMENT_CONFIRMING", "AWAITING_PAYMENT");
        assertThat(data.get("total").asLong()).isEqualTo(6);
        assertThat(data.get("counts").get("all").asLong()).isEqualTo(6);
        assertThat(data.get("counts").get("won").asLong()).isEqualTo(1);

        JsonNode won = data.get("items").get(0);
        assertThat(won.get("wonOrderId").asString()).isEqualTo(wonOrder(e.won.entry).toString());
        assertThat(won.get("draw").get("drawId").asString()).isEqualTo(e.won.draw.toString());
        assertThat(won.get("draw").get("gift").get("productTitle").asString()).isEqualTo("증정품");
        assertThat(won.get("draw").get("entryFee").decimalValue()).isEqualByComparingTo("300");
        assertThat(Instant.parse(won.get("enteredAt").asString())).isEqualTo(base.plus(Duration.ofHours(6)));
        assertThat(data.get("items").get(1).get("wonOrderId").isNull()).as("낙첨은 당첨 주문이 없다").isTrue();
        assertThat(data.get("items").get(4).get("draw").get("drawId").asString()).isEqualTo(e.authorizing.draw.toString());
    }

    @Test
    @DisplayName("당첨 탭은 당첨만 — total 은 당첨 건수, counts 는 두 탭 그대로. 페이지는 size 단위로 자른다")
    void wonTabAndPaging() throws Exception {
        Entries e = sixEntries();

        JsonNode won = data(list(customerId, "filter=WON").andExpect(status().isOk()));
        assertThat(won.get("items")).hasSize(1);
        assertThat(won.get("items").get(0).get("entryId").asString()).isEqualTo(e.won.entry.toString());
        assertThat(won.get("total").asLong()).isEqualTo(1);
        assertThat(won.get("counts").get("all").asLong()).isEqualTo(6);

        JsonNode second = data(list(customerId, "page=1&size=2").andExpect(status().isOk()));
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("size").asInt()).isEqualTo(2);
        assertThat(second.get("total").asLong()).isEqualTo(6);
        assertThat(List.of(second.get("items").get(0).get("entryId").asString(), second.get("items").get(1).get("entryId").asString()))
                .containsExactly(e.result.entry.toString(), e.closedUnpaid.entry.toString());
    }

    @Test
    @DisplayName("응모 시각이 같으면 응모 id 역순 — 쪽을 넘겨도 순서가 흔들리지 않는다")
    void sameEnteredAtFallsBackToIdDescending() throws Exception {
        Instant at = base.plus(Duration.ofHours(1));
        UUID low = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff");
        UUID first = draw(base.minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)), 1);
        UUID second = draw(base.minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(1)), 1);
        entry(low, first, customerId, at);
        entry(high, second, customerId, at);

        JsonNode data = data(list(customerId, "").andExpect(status().isOk()));

        assertThat(List.of(data.get("items").get(0).get("entryId").asString(), data.get("items").get(1).get("entryId").asString()))
                .containsExactly(high.toString(), low.toString());
    }

    @Test
    @DisplayName("응모가 없으면 빈 목록 · 건수 0")
    void noEntries() throws Exception {
        JsonNode data = data(list(customerId, "").andExpect(status().isOk()));

        assertThat(data.get("items")).isEmpty();
        assertThat(data.get("total").asLong()).isZero();
        assertThat(data.get("counts").get("all").asLong()).isZero();
        assertThat(data.get("counts").get("won").asLong()).isZero();
    }

    @Test
    @DisplayName("회원만 — 익명 401, 관리자 403. 모르는 filter · 범위 밖 size · 음수 page 는 400")
    void membersOnlyAndValidation() throws Exception {
        mockMvc.perform(get("/api/v1/draws/entries/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/draws/entries/me").with(TestAuth.admin())).andExpect(status().isForbidden());
        list(customerId, "filter=LOST").andExpect(status().isBadRequest());
        list(customerId, "size=101").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        list(customerId, "page=-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── 도우미 ──

    record Placed(UUID draw, UUID entry) {
    }

    record Entries(Placed awaiting, Placed authorizing, Placed closedUnpaid, Placed result, Placed lost, Placed won) {
    }

    /** 배지마다 하나씩, 응모 시각은 넣은 순서대로 한 시간씩 늦다(맨 뒤 당첨이 최신). */
    private Entries sixEntries() {
        Instant past = Instant.now().minus(Duration.ofMinutes(1));
        Instant future = Instant.now().plus(Duration.ofDays(1));
        Placed awaiting = placed(future, 1, "AWAITING_PAYMENT", null, 1);
        Placed authorizing = placed(past, 1, "AUTHORIZING", "draw_window_1", 2);   // 마감 전에 시작해 마감 뒤에도 승인 중
        Placed closedUnpaid = placed(past, 1, "AWAITING_PAYMENT", null, 3);
        Placed result = placed(past, 1, "PAID", null, 4);
        Placed lost = placed(past, 1, "PAID", null, 5);
        jdbcTemplate.update("UPDATE draw_campaigns SET settled_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) bytes(lost.draw));
        Placed won = placed(past, 1, "PAID", null, 6);
        assertThat(settlement.close(won.draw)).as("결제 완료가 당첨 인원 이하 — 전원 당첨으로 반영").isTrue();
        assertThat(wonOrder(won.entry)).isNotNull();
        return new Entries(awaiting, authorizing, closedUnpaid, result, lost, won);
    }

    private Placed placed(Instant closes, int winners, String status, String window, int hour) {
        UUID draw = draw(base.minus(Duration.ofDays(1)), closes, winners);
        return new Placed(draw, entry(draw, customerId, status, window, base.plus(Duration.ofHours(hour))));
    }

    /** 회차와 당첨 인원만큼의 재고 확보(회차 만들기와 같다). */
    private UUID draw(Instant opens, Instant closes, int winners) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_campaigns (id, idempotency_key, product_id, option_id, title, product_title_snapshot, option_title_snapshot,
                                            entry_fee, winner_count, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, '드로우', '증정품', '블랙', 300, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(id), id.toString(), bytes(product.productId()), bytes(option), winners, utc(opens), utc(closes));
        fixtures.stock(option, 10, winners, 0);
        return id;
    }

    private UUID entry(UUID draw, UUID customer, String status, String window, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_entries (id, campaign_id, customer_id, status, authorizing_provider_order_id, ship_to_name, ship_to_phone,
                                          ship_to_postal_code, ship_to_line1, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '홍길동', '010-0000-0000', '04524', '서울시 중구', ?, UTC_TIMESTAMP(6))""",
                bytes(id), bytes(draw), bytes(customer), status, window, utc(createdAt));
        return id;
    }

    private void entry(UUID id, UUID draw, UUID customer, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO draw_entries (id, campaign_id, customer_id, status, ship_to_name, ship_to_phone, ship_to_postal_code, ship_to_line1,
                                          created_at, updated_at)
                VALUES (?, ?, ?, 'AWAITING_PAYMENT', '홍길동', '010-0000-0000', '04524', '서울시 중구', ?, UTC_TIMESTAMP(6))""",
                bytes(id), bytes(draw), bytes(customer), utc(createdAt));
    }

    private UUID wonOrder(UUID entry) {
        return jdbcTemplate.queryForObject("SELECT BIN_TO_UUID(won_order_id) FROM draw_entries WHERE id = ?", UUID.class, (Object) bytes(entry));
    }

    /** 앱은 datetime 을 UTC 로 읽는다(hibernate.jdbc.time_zone). Timestamp 로 넘기면 JVM 시간대로 바뀌어 시각이 어긋난다. */
    private static String utc(Instant at) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC).format(at);
    }

    private ResultActions list(UUID customer, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/draws/entries/me" + (query.isEmpty() ? "" : "?" + query)).with(TestAuth.customer(customer)));
    }

    private static JsonNode data(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }
}
