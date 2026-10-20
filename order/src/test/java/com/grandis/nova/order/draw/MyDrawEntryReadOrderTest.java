package com.grandis.nova.order.draw;

import com.grandis.nova.order.draw.domain.repository.DrawEntryStore;
import com.grandis.nova.order.support.GeneralLog;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 응모(GET /api/v1/draws/{drawId}/entries/me)의 읽는 순서 — 반영 표식을 먼저, 응모를 나중에 읽는다. 세 문장을 트랜잭션 없이 차례로 읽으므로
 * 그 사이 결과 반영이 커밋될 수 있다. 응모 저장소를 spy 로 감싸 응모를 읽은 직후 다른 연결에서 결과 반영(전원 당첨)을 커밋한다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class MyDrawEntryReadOrderTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DrawSettlement settlement;
    @MockitoSpyBean DrawEntryStore entries;

    @Test
    @DisplayName("응모를 읽은 직후 결과 반영이 커밋돼도 당첨자에게 낙첨이 보이지 않는다 — 이번 응답은 결과 대기(null), 다음 조회는 당첨")
    void settlementBetweenReadsNeverShowsWinnerAsLost() throws Exception {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        UUID customerId = fixtures.customer();
        UUID draw = closedDraw(fixtures);
        insertPaidEntry(draw, customerId);
        boolean[] settled = {false};
        doAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            if (!settled[0]) {
                settled[0] = onAnotherThread(() -> settlement.close(draw));
            }
            return found;
        }).when(entries).findByCampaignAndCustomer(any(), any());

        JsonNode during = data(mockMvc.perform(get("/api/v1/draws/{id}/entries/me", draw).with(TestAuth.customer(customerId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(settled[0]).as("응모를 읽은 뒤 결과 반영이 정말 커밋됐다").isTrue();
        assertThat(during.get("result").isNull()).as("반영 전 표식을 읽었으니 결과 대기 — 낙첨이 아니다").isTrue();
        JsonNode after = data(mockMvc.perform(get("/api/v1/draws/{id}/entries/me", draw).with(TestAuth.customer(customerId))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(after.get("result").asString()).as("대조군 — 반영 뒤 조회는 당첨").isEqualTo("WON");
    }

    @Test
    @DisplayName("내 응모는 반영 표식 → 응모 → 회차 세 문장을 이 순서로, 트랜잭션 없이 읽는다(준비 · 정리 문장이 없다)")
    void readsCloseStateThenEntryThenCampaignWithoutTransaction() throws Exception {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        UUID customerId = fixtures.customer();
        UUID draw = closedDraw(fixtures);
        insertPaidEntry(draw, customerId);

        List<String> statements = GeneralLog.statements("%settled_at AS settledAt FROM draw_campaigns WHERE id%", () ->
                mockMvc.perform(get("/api/v1/draws/{id}/entries/me", draw).with(TestAuth.customer(customerId))).andExpect(status().isOk()));

        assertThat(statements).as("트랜잭션 준비 · 정리 문장").noneMatch(GeneralLog::isTransactionHousekeeping);
        assertThat(statements).as("반영 표식 → 응모 → 회차").hasSize(3);
        assertThat(statements.get(0)).contains("settled_at AS settledAt FROM draw_campaigns");
        assertThat(statements.get(1)).contains("from draw_entries");
        assertThat(statements.get(2)).contains("from draw_campaigns");
    }

    /** 마감한 회차와 당첨 인원(1)만큼의 재고 확보(회차 만들기와 같다). */
    private UUID closedDraw(OrderFixtures fixtures) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO draw_campaigns (id, idempotency_key, product_id, option_id, title, product_title_snapshot, option_title_snapshot,
                                            entry_fee, winner_count, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, '드로우', '증정품', '블랙', 300, 1, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(id), id.toString(), bytes(product.productId()), bytes(option), utc(now.minus(Duration.ofDays(1))), utc(now.minus(Duration.ofMinutes(1))));
        fixtures.stock(option, 10, 1, 0);
        return id;
    }

    private void insertPaidEntry(UUID draw, UUID customer) {
        jdbcTemplate.update("""
                INSERT INTO draw_entries (id, campaign_id, customer_id, status, ship_to_name, ship_to_phone, ship_to_postal_code, ship_to_line1,
                                          created_at, updated_at)
                VALUES (?, ?, ?, 'PAID', '홍길동', '010-0000-0000', '04524', '서울시 중구', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(UUID.randomUUID()), bytes(draw), bytes(customer));
    }

    private static String utc(Instant at) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC).format(at);
    }

    private static <T> T onAnotherThread(java.util.concurrent.Callable<T> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(work).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static JsonNode data(String body) {
        return JSON.readTree(body).get("data");
    }
}
