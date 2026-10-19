package com.grandis.nova.order.draw;

import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회차 목록의 단계 필터(phase) — 회원 · 관리자 목록. 표를 다른 시험과 함께 쓰므로 "이 시험이 만든 회차" 와 "받은 항목의 단계 칸" 으로 판정한다.
 * 같은 Spring 컨텍스트를 쓰려고 대역 구성을 AdminDrawApiTest 와 같게 둔다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
@DisplayName("드로우 회차 목록 — 단계 필터")
class DrawListPhaseApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DrawCampaignStore campaigns;
    @MockitoBean CatalogClient catalogClient;
    /** 쓰지 않지만 AdminDrawApiTest 와 같은 대역 구성으로 두어 Spring 컨텍스트를 함께 쓴다. */
    @MockitoSpyBean CartStore store;

    OrderFixtures fixtures;
    Map<DrawPhase, UUID> mine;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        Instant now = Instant.now();
        mine = Map.of(
                DrawPhase.SCHEDULED, create(now.plus(Duration.ofHours(1)), now.plus(Duration.ofDays(1))),
                DrawPhase.OPEN, create(now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1))),
                DrawPhase.CLOSED, create(now.minus(Duration.ofDays(1)), now.minus(Duration.ofHours(1))));
    }

    @Test
    @DisplayName("회원 · 관리자 목록 — phase 를 주면 그 단계의 회차만(받은 항목의 단계 칸이 전부 그 값), 이 시험의 회차 중 그 단계 하나만 · 비우면 셋 다")
    void filtersByPhase() throws Exception {
        for (String path : List.of("/api/v1/draws", "/api/v1/admin/draws")) {
            for (DrawPhase phase : DrawPhase.values()) {
                JsonNode page = data(list(path, phase.name()));
                List<String> ids = new ArrayList<>();
                page.get("items").forEach(item -> {
                    assertThat(item.get("phase").asString()).as("%s %s", path, phase).isEqualTo(phase.name());
                    ids.add(item.get("drawId").asString());
                });
                for (DrawPhase other : DrawPhase.values()) {
                    if (other == phase) {
                        assertThat(ids).as("%s %s 에 내 %s 회차", path, phase, other).contains(mine.get(other).toString());
                    } else {
                        assertThat(ids).as("%s %s 에 내 %s 회차", path, phase, other).doesNotContain(mine.get(other).toString());
                    }
                }
                assertThat(page.get("total").asLong()).as("건수도 같은 조건").isEqualTo(count(phase));
            }
            List<String> all = new ArrayList<>();
            data(list(path, null)).get("items").forEach(item -> all.add(item.get("drawId").asString()));
            assertThat(all).as(path + " 전부").contains(mine.values().stream().map(UUID::toString).toArray(String[]::new));
        }
    }

    @Test
    @DisplayName("모르는 phase 는 400(phase) — 회원 · 관리자")
    void rejectsUnknownPhase() throws Exception {
        for (String path : List.of("/api/v1/draws", "/api/v1/admin/draws")) {
            list(path, "DRAWN").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.violations[0].field").value("phase"));
        }
    }

    @Test
    @DisplayName("빈 phase(phase=)는 거르지 않는다 — 이 시험의 세 단계 회차가 모두 나온다(회원 · 관리자)")
    void blankPhaseListsAll() throws Exception {
        for (String path : List.of("/api/v1/draws", "/api/v1/admin/draws")) {
            List<String> ids = new ArrayList<>();
            data(list(path, "")).get("items").forEach(item -> ids.add(item.get("drawId").asString()));
            assertThat(ids).as(path).contains(mine.values().stream().map(UUID::toString).toArray(String[]::new));
        }
    }

    @Test
    @DisplayName("경계는 단계 칸(phaseAt)과 같다 — 시작 시각 그 순간부터 응모 중(1µs · 1ns 전은 예정), 마감 시각 그 순간부터 마감(1µs · 1ns 전은 응모 중). now 가 µs 아래를 가져도 같다")
    void boundariesMatchPhaseAt() {
        Instant opensAt = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS);
        Instant closesAt = opensAt.plus(Duration.ofHours(1));
        UUID drawId = create(opensAt, closesAt);
        DrawCampaign campaign = campaigns.findById(drawId).orElseThrow();
        for (Instant now : List.of(opensAt.minusNanos(1_000), opensAt.minusNanos(400), opensAt.minusNanos(1), opensAt,
                closesAt.minusNanos(1_000), closesAt.minusNanos(1), closesAt)) {
            DrawPhase expected = campaign.phaseAt(now);
            for (DrawPhase phase : DrawPhase.values()) {
                boolean listed = new TransactionTemplate(transactionManager).execute(status -> campaigns.findNewestFirst(phase, now, 0, 100))
                        .items().stream().anyMatch(c -> c.id().equals(drawId));
                assertThat(listed).as("now=%s phase=%s (phaseAt=%s)", now, phase, expected).isEqualTo(phase == expected);
            }
        }
        assertThat(campaign.phaseAt(opensAt.minusNanos(1_000))).isEqualTo(DrawPhase.SCHEDULED);
        assertThat(campaign.phaseAt(opensAt)).isEqualTo(DrawPhase.OPEN);
        assertThat(campaign.phaseAt(closesAt)).isEqualTo(DrawPhase.CLOSED);
    }

    private ResultActions list(String path, String phase) throws Exception {
        MockHttpServletRequestBuilder request = get(path).param("size", "100");
        if (phase != null) {
            request.param("phase", phase);
        }
        return mockMvc.perform(path.contains("/admin/") ? request.with(TestAuth.admin()) : request);
    }

    private long count(DrawPhase phase) {
        String where = switch (phase) {
            case SCHEDULED -> "opens_at > UTC_TIMESTAMP(6)";
            case OPEN -> "opens_at <= UTC_TIMESTAMP(6) AND closes_at > UTC_TIMESTAMP(6)";
            case CLOSED -> "closes_at <= UTC_TIMESTAMP(6)";
        };
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM draw_campaigns WHERE " + where, Long.class);
    }

    private UUID create(Instant opensAt, Instant closesAt) {
        OrderFixtures.StockProduct gift = fixtures.inStockProduct(1);
        return new TransactionTemplate(transactionManager).execute(status -> campaigns.insert(new NewDrawCampaign(UUID.randomUUID().toString(),
                gift.productId(), gift.optionIds().getFirst(), "단계 시험", "증정품", null, "기본", null, new BigDecimal("100"), 1, opensAt, closesAt)).id());
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }
}
