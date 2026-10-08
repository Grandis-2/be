package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@PreorderIntegrationTest
class PreorderCampaignAdminServiceTest {

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

    @RepeatedTest(3)
    void 동시에_처음_회차를_만들어도_한_행만_남는다() throws Exception {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(UUID.randomUUID()));
        Instant opensAt = Instant.now().plusSeconds(3600);

        List<Outcome<PreorderCampaign>> outcomes = Concurrently.run(4, i -> () ->
                service.upsertCampaign(productId, opensAt.plusSeconds(i), opensAt.plusSeconds(i + 7200)));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(fixtures.count("SELECT COUNT(*) FROM preorder_campaigns WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId)))
                .isEqualTo(1);
    }
}
