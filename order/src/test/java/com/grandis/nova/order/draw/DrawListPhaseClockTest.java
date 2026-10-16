package com.grandis.nova.order.draw;

import com.grandis.nova.common.jpa.StorageClock;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 목록의 단계 필터와 응답의 단계 칸이 같은 앱 시계(storageClock)로 갈리는가 — 시계를 경계 정각에 고정하고 API 로 본다.
 * 실제 시각과 수년 떨어진 시각이라, 컨트롤러가 어느 한쪽에서라도 앱 시계 대신 실제 시각을 쓰면 목록이나 칸이 어긋난다.
 * 주기 작업(추첨 · 정체 점검)은 OrderIntegrationTest 가 1시간 뒤로 미뤄 고정 시계로 다른 회차를 처리하지 않는다.
 * 만든 회차는 끝나면 지운다 — 만든 시각도 고정 시각(수년 뒤)이라, 남기면 표를 함께 쓰는 다른 시험의 최신순 목록 맨 앞을 차지한다
 * (이 시험이 먼저 돌면 AdminDrawApiTest.listsNewestFirstWithPhase 가 실패했다).
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
@DisplayName("드로우 회차 목록 — 단계 필터는 앱 시계로")
class DrawListPhaseClockTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** 고정 시각 — 이 순간 열리는 회차는 응모 중, 이 순간 닫히는 회차는 마감이다. */
    static final Instant NOW = Instant.parse("2031-03-01T00:00:00Z");

    @TestBean(name = "storageClock", methodName = "fixedClock")
    Clock storageClock;

    static Clock fixedClock() {
        return StorageClock.atStorageResolution(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DrawCampaignStore campaigns;

    final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeCreated() {
        created.forEach(id -> jdbcTemplate.update("DELETE FROM draw_campaigns WHERE id = ?", (Object) OrderFixtures.bytes(id)));
    }

    @Test
    @DisplayName("시작 시각 정각의 회차는 응모 중 목록에, 마감 시각 정각의 회차는 마감 목록에 — 받은 항목의 단계 칸도 그 값(회원 · 관리자)")
    void filterAndPhaseFieldUseAppClock() throws Exception {
        UUID opensNow = create(NOW, NOW.plus(Duration.ofHours(1)));
        UUID closesNow = create(NOW.minus(Duration.ofHours(1)), NOW);
        for (String path : List.of("/api/v1/draws", "/api/v1/admin/draws")) {
            for (DrawPhase phase : DrawPhase.values()) {
                Map<String, String> listed = phases(path, phase);
                assertThat(listed.values()).as("%s %s 의 단계 칸", path, phase).allMatch(phase.name()::equals);
                assertThat(listed.containsKey(opensNow.toString())).as("%s %s 에 시작 정각 회차", path, phase).isEqualTo(phase == DrawPhase.OPEN);
                assertThat(listed.containsKey(closesNow.toString())).as("%s %s 에 마감 정각 회차", path, phase).isEqualTo(phase == DrawPhase.CLOSED);
            }
        }
    }

    /** 그 단계로 거른 목록의 회차 id → 단계 칸. */
    private Map<String, String> phases(String path, DrawPhase phase) throws Exception {
        MockHttpServletRequestBuilder request = get(path).param("phase", phase.name()).param("size", "100");
        String body = mockMvc.perform(path.contains("/admin/") ? request.with(TestAuth.admin()) : request)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, String> listed = new HashMap<>();
        JsonNode items = JSON.readTree(body).get("data").get("items");
        items.forEach(item -> listed.put(item.get("drawId").asString(), item.get("phase").asString()));
        return listed;
    }

    private UUID create(Instant opensAt, Instant closesAt) {
        OrderFixtures.StockProduct gift = new OrderFixtures(jdbcTemplate).inStockProduct(1);
        UUID id = new TransactionTemplate(transactionManager).execute(status -> campaigns.insert(new NewDrawCampaign(UUID.randomUUID().toString(),
                gift.productId(), gift.optionIds().getFirst(), "시계 시험", "증정품", "기본", null, new BigDecimal("100"), 1, opensAt, closesAt)).id());
        created.add(id);
        return id;
    }
}
