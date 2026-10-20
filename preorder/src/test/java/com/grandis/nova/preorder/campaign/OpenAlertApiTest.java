package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.support.CampaignRowLock;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 오픈 알림 신청 · 해제 · 조회. 판정은 회차 행의 상품 사본으로 한다 — catalog 를 부르지 않는다(대역 없음). */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class OpenAlertApiTest {

    static final String ALERT = "/api/v1/preorders/products/{productId}/open-alert";
    static final String MINE = "/api/v1/preorders/open-alerts";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    DataSource dataSource;

    ShopFixtures fixtures;
    UUID customerId;
    Instant opensAt;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        opensAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    void 신청은_다시_해도_한_건이고_상품_토글과_내_목록에_보인다() throws Exception {
        UUID productId = upcoming(opensAt, "아이폰 Duo", "ACTIVE", true);

        for (int i = 0; i < 2; i++) {
            subscribe(productId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.productId").value(productId.toString()))
                    .andExpect(jsonPath("$.data.subscribed").value(true));
        }

        assertThat(rows(productId)).isEqualTo(1);
        subscribed(productId).andExpect(status().isOk()).andExpect(jsonPath("$.data.subscribed").value(true));
        JsonNode item = itemOf(productId);
        assertThat(item.get("title").asString()).isEqualTo("아이폰 Duo");
        assertThat(Instant.parse(item.get("opensAt").asString())).isEqualTo(opensAt);
        assertThat(Instant.parse(item.get("closesAt").asString())).isEqualTo(opensAt.plus(1, ChronoUnit.DAYS));
    }

    @Test
    void 해제는_신청이_없어도_204_이고_토글과_목록에서_빠진다() throws Exception {
        UUID productId = upcoming(opensAt, "아이폰 Duo", "ACTIVE", true);
        subscribe(productId).andExpect(status().isOk());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete(ALERT, productId).with(customer(customerId))).andExpect(status().isNoContent());
        }
        mockMvc.perform(delete(ALERT, UUID.randomUUID()).with(customer(customerId))).andExpect(status().isNoContent());

        assertThat(rows(productId)).isZero();
        subscribed(productId).andExpect(jsonPath("$.data.subscribed").value(false));
        assertThat(productIds(mine())).doesNotContain(productId);
    }

    @Test
    void 회차가_없거나_비공개면_404_이미_열렸으면_409_판매_중지면_409_이고_아무것도_넣지_않는다() throws Exception {
        UUID hidden = upcoming(opensAt, "숨김", "ACTIVE", false);
        UUID paused = upcoming(opensAt, "중지", "PAUSED", true);
        UUID opened = fixtures.openPreorderProduct().productId();
        fixtures.listing(opened, "오픈", "ACTIVE", true);

        expectError(subscribe(UUID.randomUUID()), 404, "PRODUCT_NOT_FOUND");
        expectError(subscribe(hidden), 404, "PRODUCT_NOT_FOUND");
        expectError(subscribe(opened), 409, "PRODUCT_ALREADY_OPEN");
        expectError(subscribe(paused), 409, "PRODUCT_PAUSED");

        assertThat(rows(hidden) + rows(paused) + rows(opened)).isZero();
    }

    /**
     * 표시 뒤의 신청은 발송에 들지 못하므로 막는다. 오픈 시각 전이어도(인스턴스 간 시계 차) 표시가 됐으면 409 다 —
     * 표시는 신청의 공유 잠금을 기다리므로, 잠금을 얻은 신청이 표시를 보면 그 신청은 표시보다 늦다.
     */
    @Test
    void 오픈_알림을_이미_표시한_회차는_오픈_전이어도_409() throws Exception {
        UUID productId = upcoming(opensAt, "아이폰 Duo", "ACTIVE", true);
        jdbcTemplate.update("UPDATE preorder_campaigns SET open_notified_at = UTC_TIMESTAMP(6) WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId));

        expectError(subscribe(productId), 409, "PRODUCT_ALREADY_OPEN");
        assertThat(rows(productId)).isZero();
    }

    /** 열린 뒤의 신청은 어차피 409 다 — 회차 캐시로 잠그기 전에 거절해 접수가 쥔 회차 잠금 뒤에 줄 서지 않는다. */
    @Test
    void 열린_회차는_회차_잠금이_쥐어져도_기다리지_않고_409() throws Exception {
        UUID opened = fixtures.openPreorderProduct().productId();

        try (CampaignRowLock ignored = CampaignRowLock.hold(dataSource, opened)) {
            long started = System.nanoTime();
            expectError(subscribe(opened), 409, "PRODUCT_ALREADY_OPEN");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        }
        assertThat(rows(opened)).isZero();
    }

    /** 판매 상태 사본을 아직 받지 못한 회차(catalog 값 전)는 막지 않는다 — 목록에는 상품명 대신 대체 문구가 간다. */
    @Test
    void 상품_사본이_없는_회차도_신청되고_목록의_상품명은_대체_문구다() throws Exception {
        UUID productId = fixtures.preorderProduct(opensAt, opensAt.plus(1, ChronoUnit.DAYS)).productId();

        subscribe(productId).andExpect(status().isOk());

        assertThat(itemOf(productId).get("title").asString()).isEqualTo("사전예약 상품");
    }

    @Test
    void 목록은_오픈_전_공개_회차만_오픈이_이른_순이다() throws Exception {
        UUID later = upcoming(opensAt.plus(2, ChronoUnit.HOURS), "나중", "ACTIVE", true);
        UUID sooner = upcoming(opensAt, "먼저", "ACTIVE", true);
        UUID pausedLater = upcoming(opensAt.plus(1, ChronoUnit.HOURS), "중지", "ACTIVE", true);
        UUID hiddenLater = upcoming(opensAt.plus(3, ChronoUnit.HOURS), "숨김", "ACTIVE", true);
        UUID openedLater = upcoming(opensAt.plus(4, ChronoUnit.HOURS), "열림", "ACTIVE", true);
        for (UUID productId : List.of(later, sooner, pausedLater, hiddenLater, openedLater)) {
            subscribe(productId).andExpect(status().isOk());
        }
        fixtures.listing(pausedLater, "중지", "PAUSED", true);
        fixtures.listing(hiddenLater, "숨김", "ACTIVE", false);
        jdbcTemplate.update("UPDATE preorder_campaigns SET opens_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE product_id = ?",
                (Object) UuidBinary.toBytes(openedLater));

        assertThat(productIds(mine())).as("판매 중지는 보여 끌 수 있게, 비공개 · 열린 회차는 빠진다")
                .containsExactly(sooner, pausedLater, later);
    }

    @Test
    void 회원만_쓴다_익명은_401_관리자는_403() throws Exception {
        UUID productId = upcoming(opensAt, "아이폰 Duo", "ACTIVE", true);

        mockMvc.perform(put(ALERT, productId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(MINE)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(ALERT, productId).with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(get(ALERT, productId).with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(delete(ALERT, productId).with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(get(MINE).with(admin())).andExpect(status().isForbidden());
        assertThat(rows(productId)).isZero();
    }

    @Test
    void 다른_회원의_신청은_보이지_않는다() throws Exception {
        UUID productId = upcoming(opensAt, "아이폰 Duo", "ACTIVE", true);
        UUID other = fixtures.customer();
        mockMvc.perform(put(ALERT, productId).with(customer(other))).andExpect(status().isOk());

        subscribed(productId).andExpect(jsonPath("$.data.subscribed").value(false));
        assertThat(productIds(mine())).doesNotContain(productId);
    }

    private UUID upcoming(Instant opens, String title, String status, boolean visible) {
        UUID productId = fixtures.preorderProduct(opens, opens.plus(1, ChronoUnit.DAYS)).productId();
        fixtures.listing(productId, title, status, visible);
        return productId;
    }

    private ResultActions subscribe(UUID productId) throws Exception {
        return mockMvc.perform(put(ALERT, productId).with(customer(customerId)));
    }

    private ResultActions subscribed(UUID productId) throws Exception {
        return mockMvc.perform(get(ALERT, productId).with(customer(customerId)));
    }

    private JsonNode mine() throws Exception {
        return jsonMapper.readTree(mockMvc.perform(get(MINE).with(customer(customerId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/items");
    }

    private JsonNode itemOf(UUID productId) throws Exception {
        for (JsonNode item : mine()) {
            if (item.get("productId").asString().equals(productId.toString())) {
                return item;
            }
        }
        throw new AssertionError("내 목록에 없다: " + productId);
    }

    private static List<UUID> productIds(JsonNode items) {
        List<UUID> ids = new ArrayList<>();
        items.forEach(item -> ids.add(UUID.fromString(item.get("productId").asString())));
        return ids;
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private int rows(UUID productId) {
        return fixtures.count("SELECT COUNT(*) FROM preorder_open_alerts WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId));
    }
}
