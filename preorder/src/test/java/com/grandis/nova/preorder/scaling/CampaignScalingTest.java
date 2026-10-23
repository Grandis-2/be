package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.ScalingDemand;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 증설 대상 회차와 회차 변경 뒤 바로 다시 맞추기. 공유 DB 에는 다른 시험의 회차가 섞이므로 이 시험만 쓰는 큰 예상 방문자 수로
 * 들었는지 · 빠졌는지 본다. 꺼진 기본 설정이라 AWS 대신 로그로만 남기는 구현이 돈다.
 */
@PreorderIntegrationTest
class CampaignScalingTest {

    /** 관리자 API 상한(1억)과 그 바로 아래 — 다른 시험은 이렇게 큰 값을 쓰지 않는다. */
    static final int EXCLUDED = 100_000_000;
    static final int INCLUDED = 99_999_999;

    @Autowired
    Campaigns campaigns;

    @Autowired
    PreorderCampaignAdminService campaignService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

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
    void 공개_판매_중이고_오픈이_유지_구간부터_여유_구간_안인_회차만_센다() {
        Instant now = Instant.now();
        UUID justOpened = campaign(now.minusSeconds(600), now.plusSeconds(7200), INCLUDED);
        campaign(now.minusSeconds(7200), now.plusSeconds(7200), EXCLUDED);
        campaign(now.plusSeconds(7200), now.plusSeconds(10800), EXCLUDED);
        campaign(now.minusSeconds(1200), now.minusSeconds(60), EXCLUDED);
        fixtures.listing(campaign(now.plusSeconds(600), now.plusSeconds(7200), EXCLUDED), "숨김", "ACTIVE", false);
        fixtures.listing(campaign(now.plusSeconds(600), now.plusSeconds(7200), EXCLUDED), "중지", "PAUSED", true);

        ScalingDemand demand = campaigns.findScalingDemand(now, now.minusSeconds(1800), now.plusSeconds(900));

        assertThat(demand.maxExpectedVisitors()).as("오픈 뒤 유지 구간 안 회차가 가장 크다").isEqualTo(INCLUDED);
        assertThat(demand.campaigns()).isPositive();
        fixtures.expectedVisitors(justOpened, null);
    }

    /** 다음 주기(1분)를 기다리지 않는다 — 커밋 뒤 전용 스레드에서 바로 한 번 맞춘다. */
    @Test
    void 회차를_바꾸면_커밋_뒤_바로_다시_맞춘다() {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(UUID.randomUUID()));
        Instant opensAt = Instant.now().plusSeconds(720).truncatedTo(ChronoUnit.SECONDS);
        clearInvocations(floors);

        campaignService.upsertCampaign(productId, opensAt, opensAt.plusSeconds(3600), 20_000);

        verify(floors, timeout(5_000).times(2)).ensure(anyString(), anyInt());
    }

    /** 먼저 읽은 태스크의 늦은 호출이 나중에 읽은 값을 덮지 않게 태스크 사이에서 한 번에 하나만 맞춘다. */
    @Test
    void 맞추기_잠금은_한_번에_하나이고_제_것만_푼다() {
        ScalingLock lock = new ScalingLock(redis, Duration.ofSeconds(5));
        String first = lock.tryAcquire();
        try {
            assertThat(first).isNotNull();
            assertThat(lock.tryAcquire()).isNull();

            lock.release("다른 태스크의 표식");
            assertThat(lock.tryAcquire()).isNull();
        } finally {
            lock.release(first);
        }
        String next = lock.tryAcquire();
        assertThat(next).isNotNull();
        lock.release(next);
    }

    /** 없거나 0 이상 정수가 아니면 관리자가 정한 적이 없는 것으로 본다. 읽기만 하고 쓰지 않는다. */
    @Test
    void 관리자_입장_속도는_0_이상_정수만_읽는다() {
        AdmissionCredit credit = new AdmissionCredit(redis);
        try {
            redis.delete(AdmissionCredit.KEY);
            assertThat(credit.read()).isEmpty();
            for (String broken : new String[]{"abc", "-5", ""}) {
                redis.opsForHash().put(AdmissionCredit.KEY, AdmissionCredit.FIELD, broken);
                assertThat(credit.read()).as(broken).isEmpty();
            }
            redis.opsForHash().put(AdmissionCredit.KEY, AdmissionCredit.FIELD, "250");
            assertThat(credit.read()).isEqualTo(OptionalLong.of(250));
        } finally {
            redis.delete(AdmissionCredit.KEY);
        }
    }

    private UUID campaign(Instant opensAt, Instant closesAt, Integer expectedVisitors) {
        UUID productId = fixtures.preorderProduct(opensAt, closesAt).productId();
        fixtures.expectedVisitors(productId, expectedVisitors);
        return productId;
    }
}
