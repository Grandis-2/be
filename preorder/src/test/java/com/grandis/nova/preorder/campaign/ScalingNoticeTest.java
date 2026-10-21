package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 켜 두면 회차 일정 · 예상 방문자 수가 바뀔 때마다 대기열용 회차 변경 이벤트와 같은 번호로 scaling-events 에도 적는다. */
@PreorderIntegrationTest
// 마감 알림 · catalog 일정 알림 시험과 같은 설정이라 스프링 컨텍스트를 함께 쓴다
@TestPropertySource(properties = {"nova.campaign.close-notice.enabled=true", "nova.campaign.close-notice.interval=1h",
        "nova.campaign.schedule-notice.enabled=true",
        "nova.campaign.scaling-notice.enabled=true"})
class ScalingNoticeTest {

    @Autowired
    PreorderCampaignAdminService service;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 일정과_예상_방문자_수를_일정_번호와_함께_적는다() {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(UUID.randomUUID()));
        Instant opensAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        service.upsertCampaign(productId, opensAt, opensAt.plusSeconds(3600));

        service.upsertCampaign(productId, opensAt, opensAt.plusSeconds(3600), 20_000);

        List<Map<String, Object>> plans = plans(productId);
        assertThat(plans).extracting(plan -> plan.get("change")).containsExactly("CREATED", "RESCHEDULED");
        assertThat(plans).extracting(plan -> plan.get("version")).containsExactly(1L, 2L);
        assertThat(plans).extracting(plan -> plan.get("expectedVisitors")).containsExactly("null", "20000");
        assertThat(plans.getLast().get("opensAt")).isEqualTo(opensAt.toString());
        assertThat(plans.getLast().get("visible")).isEqualTo("true");
        assertThat(OutboundEventType.PREORDER_CAMPAIGN_SCALING_PLANNED.destination()).isEqualTo("scaling-events");
    }

    private List<Map<String, Object>> plans(UUID productId) {
        return jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.change')) AS `change`,
                       CAST(JSON_EXTRACT(payload, '$.scheduleVersion') AS SIGNED) AS version,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.opensAt')) AS opensAt,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.visible')) AS visible,
                       CAST(JSON_EXTRACT(payload, '$.expectedVisitors') AS CHAR) AS expectedVisitors
                  FROM preorder_outbox_events
                 WHERE event_type = 'PREORDER_CAMPAIGN_SCALING_PLANNED' AND aggregate_type = 'PREORDER_CAMPAIGN'
                   AND aggregate_id = ?
                 ORDER BY id
                """, (Object) UuidBinary.toBytes(productId));
    }
}
