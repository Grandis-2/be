package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.ScalingPlan;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 증설 대상 회차와 회차 변경 뒤 바로 다시 맞추기. 공유 DB 에는 다른 시험의 회차가 섞이므로 이 시험이 만든 상품이 들었는지 · 빠졌는지로 본다.
 * 꺼진 기본 설정이라 AWS 대신 로그로만 남기는 구현이 돈다.
 */
@PreorderIntegrationTest
class CampaignScalingTest {

    @Autowired
    Campaigns campaigns;

    @Autowired
    PreorderCampaignAdminService campaignService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    CapacityFloors floors;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 공개_판매_중이고_오픈이_유지_구간부터_여유_구간_안인_회차만_준다() {
        Instant now = Instant.now();
        UUID soon = campaign(now.plusSeconds(600), now.plusSeconds(7200));
        UUID justOpened = campaign(now.minusSeconds(600), now.plusSeconds(7200));
        UUID openedLongAgo = campaign(now.minusSeconds(7200), now.plusSeconds(7200));
        UUID far = campaign(now.plusSeconds(7200), now.plusSeconds(10800));
        UUID ended = campaign(now.minusSeconds(1200), now.minusSeconds(60));
        UUID hidden = campaign(now.plusSeconds(600), now.plusSeconds(7200));
        fixtures.listing(hidden, "숨김", "ACTIVE", false);
        UUID paused = campaign(now.plusSeconds(600), now.plusSeconds(7200));
        fixtures.listing(paused, "중지", "PAUSED", true);

        List<UUID> found = campaigns.findScalingPlans(now, now.minusSeconds(1800), now.plusSeconds(900), 10_000)
                .stream().map(ScalingPlan::productId).toList();

        assertThat(found).contains(soon, justOpened).doesNotContain(openedLongAgo, far, ended, hidden, paused);
    }

    /** 다음 주기(1분)를 기다리지 않는다 — 커밋 뒤 다른 스레드에서 바로 한 번 맞춘다. */
    @Test
    void 회차를_바꾸면_커밋_뒤_바로_다시_맞추고_예상_방문자_수를_본다() {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(UUID.randomUUID()));
        Instant opensAt = Instant.now().plusSeconds(720).truncatedTo(ChronoUnit.SECONDS);
        clearInvocations(floors);

        campaignService.upsertCampaign(productId, opensAt, opensAt.plusSeconds(3600), 20_000);

        verify(floors, timeout(5_000)).ensure(eq("waitingroom"), anyInt());
        verify(floors, timeout(5_000)).ensure(eq("preorder"), eq(3));
        Instant now = Instant.now();
        assertThat(campaigns.findScalingPlans(now, now.minusSeconds(1800), now.plusSeconds(900), 10_000))
                .filteredOn(plan -> plan.productId().equals(productId))
                .extracting(ScalingPlan::expectedVisitors).containsExactly(20_000);
    }

    private UUID campaign(Instant opensAt, Instant closesAt) {
        return fixtures.preorderProduct(opensAt, closesAt).productId();
    }
}
