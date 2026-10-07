package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.preorder.campaign.CampaignRegistration.Batch;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 사전예약 상품 등록 이벤트로 회차 · 차수를 처음 만든다. 이미 있으면 그대로 둔다. */
@PreorderIntegrationTest
class CampaignRegistrarTest {

    static final List<Batch> TWO_BATCHES = List.of(
            new Batch(1, 1, 3000L, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-07")),
            new Batch(2, 3001, null, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-07")));

    @Autowired
    CampaignRegistrar registrar;

    @Autowired
    PreorderCampaignAdminService adminService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    UUID productId;
    Instant opensAt;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        productId = fixtures.product("PREORDER", "ACTIVE");
        opensAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void 회차와_차수를_한_번에_만들고_대기열로_생성_이벤트를_보낸다() {
        boolean created = registrar.register(productId, registration(opensAt));

        assertThat(created).isTrue();
        assertThat(count("SELECT COUNT(*) FROM preorder_campaigns WHERE product_id = ? AND schedule_version = 1"))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT batch_number FROM shipment_batches WHERE product_id = ? ORDER BY batch_number",
                Integer.class, (Object) UuidBinary.toBytes(productId))).containsExactly(1, 2);
        assertThat(campaignEvents()).isEqualTo(1);
    }

    @Test
    void 같은_메시지를_다시_받아도_아무것도_늘지_않는다() {
        registrar.register(productId, registration(opensAt));

        boolean again = registrar.register(productId, registration(opensAt.plusSeconds(600)));

        assertThat(again).isFalse();
        assertThat(count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?")).isEqualTo(2);
        assertThat(campaignEvents()).isEqualTo(1);
        assertThat(opensAtInDb()).isEqualTo(opensAt);
    }

    @Test
    void 관리자가_먼저_만든_회차는_덮지_않는다() {
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(UUID.randomUUID()));
        Instant adminOpensAt = opensAt.plusSeconds(1800);
        adminService.upsertCampaign(productId, adminOpensAt, adminOpensAt.plusSeconds(3600));

        assertThat(registrar.register(productId, registration(opensAt))).isFalse();

        assertThat(opensAtInDb()).isEqualTo(adminOpensAt);
        assertThat(count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?")).isZero();
    }

    @Test
    void 관리자가_비공개_상품에_먼저_만든_회차는_catalog_응답대로_비공개이고_등록_이벤트의_공개_여부만_반영한다() {
        CatalogStubs.stubPreorderProduct(catalogClient, productId, false, true,
                CatalogStubs.activeOption(UUID.randomUUID()));
        Instant adminOpensAt = opensAt.plusSeconds(1800);
        adminService.upsertCampaign(productId, adminOpensAt, adminOpensAt.plusSeconds(3600));

        assertThat(visibility()).isEqualTo("0|0|1");
        assertThat(registrar.register(productId, registration(opensAt))).isFalse();

        assertThat(visibility()).as("공개 · 번호 1 · 일정 번호 +1").isEqualTo("1|1|2");
        assertThat(opensAtInDb()).isEqualTo(adminOpensAt);
        assertThat(jdbcTemplate.queryForList("""
                SELECT CONCAT_WS('|', JSON_UNQUOTE(JSON_EXTRACT(payload, '$.change')),
                                 JSON_EXTRACT(payload, '$.visible'))
                  FROM preorder_outbox_events
                 WHERE event_type = 'PREORDER_CAMPAIGN_CHANGED' AND aggregate_id = ? ORDER BY id
                """, String.class, (Object) UuidBinary.toBytes(productId))).containsExactly("CREATED|false", "VISIBILITY|true");
    }

    @RepeatedTest(3)
    void 같은_메시지가_동시에_와도_하나만_만든다() throws Exception {
        List<Outcome<Boolean>> outcomes = Concurrently.run(4, i -> () ->
                registrar.register(productId, registration(opensAt)));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes).filteredOn(outcome -> Boolean.TRUE.equals(outcome.value())).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?")).isEqualTo(2);
        assertThat(campaignEvents()).isEqualTo(1);
    }

    @Test
    void 차수_규칙_위반_과거_오픈_마감_역전은_예외이고_아무것도_만들지_않는다() {
        List<Batch> gap = List.of(
                new Batch(1, 1, 100L, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-07")),
                new Batch(2, 200, null, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-07")));
        Instant past = Instant.now().minusSeconds(60);

        assertThatThrownBy(() -> registrar.register(productId, new CampaignRegistration(opensAt,
                opensAt.plusSeconds(3600), gap, true, 1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registrar.register(productId, registration(past)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registrar.register(productId, new CampaignRegistration(opensAt, opensAt,
                TWO_BATCHES, true, 1))).isInstanceOf(BusinessException.class);

        assertThat(count("SELECT COUNT(*) FROM preorder_campaigns WHERE product_id = ?")).isZero();
        assertThat(count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?")).isZero();
    }

    private CampaignRegistration registration(Instant opens) {
        return new CampaignRegistration(opens, opens.plusSeconds(86_400), TWO_BATCHES, true, 1);
    }

    /** 공개 여부 | 공개 여부 번호 | 일정 번호 */
    private String visibility() {
        return jdbcTemplate.queryForObject("""
                SELECT CONCAT_WS('|', visible, visibility_version, schedule_version)
                  FROM preorder_campaigns WHERE product_id = ?
                """, String.class, (Object) UuidBinary.toBytes(productId));
    }

    private int count(String sql) {
        return fixtures.count(sql, (Object) UuidBinary.toBytes(productId));
    }

    private int campaignEvents() {
        return count("""
                SELECT COUNT(*) FROM preorder_outbox_events
                 WHERE event_type = 'PREORDER_CAMPAIGN_CHANGED' AND aggregate_id = ?
                   AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.change')) = 'CREATED'
                """);
    }

    private Instant opensAtInDb() {
        return jdbcTemplate.queryForObject("SELECT opens_at FROM preorder_campaigns WHERE product_id = ?",
                LocalDateTime.class, (Object) UuidBinary.toBytes(productId)).toInstant(ZoneOffset.UTC);
    }
}
