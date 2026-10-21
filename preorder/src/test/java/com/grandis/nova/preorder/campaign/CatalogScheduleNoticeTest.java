package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
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

/** 켜 두면 회차 일정이 바뀔 때마다 대기열용 회차 변경 이벤트와 같은 값으로 catalog-events 에도 적는다(contracts/catalog-inbound-events.md). */
@PreorderIntegrationTest
// 마감 알림 시험(ClosingNotificationTest)과 같은 설정이라 스프링 컨텍스트를 함께 쓴다
@TestPropertySource(properties = {"nova.campaign.close-notice.enabled=true", "nova.campaign.close-notice.interval=1h",
        "nova.campaign.schedule-notice.enabled=true",
        "nova.campaign.scaling-notice.enabled=true"})
class CatalogScheduleNoticeTest {

    @Autowired
    PreorderCampaignAdminService service;

    @Autowired
    Campaigns campaigns;

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
    void 오픈_전_일정_변경은_새_일정과_오른_번호로_적는다() {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(UUID.randomUUID()));
        Instant opensAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        service.upsertCampaign(productId, opensAt, opensAt.plusSeconds(3600));
        Instant changed = opensAt.plusSeconds(600);

        service.upsertCampaign(productId, changed, changed.plusSeconds(3600));

        List<Map<String, Object>> notices = notices(productId);
        assertThat(notices).extracting(notice -> notice.get("change")).containsExactly("CREATED", "RESCHEDULED");
        assertThat(notices).extracting(notice -> notice.get("version")).containsExactly(1L, 2L);
        assertThat(notices.getLast().get("opensAt")).isEqualTo(changed.toString());
        assertThat(notices.getLast().get("closesAt")).isEqualTo(changed.plusSeconds(3600).toString());
        assertThat(OutboundEventType.PREORDER_CAMPAIGN_SCHEDULE_CHANGED.destination()).isEqualTo("catalog-events");
    }

    /** 마감 알림은 아직 알리지 않은 마감만 보내므로, 마감을 알린 뒤 다시 열면 이 이벤트만 나간다. */
    @Test
    void 다시_열면_새_마감_시각으로_적는다() {
        PreorderProduct product = fixtures.openPreorderProduct();
        CatalogStubs.stubPreorderProduct(catalogClient, product.productId(), CatalogStubs.activeOption(product.optionId()));
        service.closeCampaign(product.productId(), "admin");
        Instant closesAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);

        service.reopenCampaign(product.productId(), closesAt, "admin");

        List<Map<String, Object>> notices = notices(product.productId());
        assertThat(notices).extracting(notice -> notice.get("change")).containsExactly("CLOSED", "REOPENED");
        assertThat(notices).extracting(notice -> notice.get("version")).containsExactly(1L, 2L);
        assertThat(notices.getLast().get("closesAt")).isEqualTo(closesAt.toString());
    }

    /** 공개 여부만 바뀌어도 일정 번호가 오르므로 함께 적는다 — 받는 쪽 번호가 빠지지 않게. */
    @Test
    void 공개_여부가_바뀌어_번호가_오르면_함께_적는다() {
        Instant now = Instant.now();
        UUID productId = fixtures.preorderProduct(now.plusSeconds(3600), now.plusSeconds(7200)).productId();

        campaigns.applyListing(productId, new ProductListing("Nova 1", ProductStatus.ACTIVE, false, 2));

        assertThat(notices(productId)).extracting(notice -> notice.get("change")).containsExactly("VISIBILITY");
        assertThat(notices(productId)).extracting(notice -> notice.get("version")).containsExactly(1L);
    }

    private List<Map<String, Object>> notices(UUID productId) {
        return jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.change')) AS `change`,
                       CAST(JSON_EXTRACT(payload, '$.scheduleVersion') AS SIGNED) AS version,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.opensAt')) AS opensAt,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.closesAt')) AS closesAt
                  FROM preorder_outbox_events
                 WHERE event_type = 'PREORDER_CAMPAIGN_SCHEDULE_CHANGED' AND aggregate_type = 'PREORDER_CAMPAIGN'
                   AND aggregate_id = ?
                 ORDER BY id
                """, (Object) UuidBinary.toBytes(productId));
    }
}
