package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 접수 중 · 곧 열릴 회차의 경계와 상한. 시각을 직접 넣어 경계를 본다(공유 DB 라 이 시험이 만든 회차로만 판정한다). */
@PreorderIntegrationTest
class CampaignScheduleQueryTest {

    static final Duration LOOKAHEAD = Duration.ofMinutes(10);

    @Autowired
    PreorderCampaignRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void 마감_시각이_지금이면_빼고_오픈이_정확히_구간_끝이면_넣는다() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PreorderProduct closingNow = fixtures.preorderProduct(now.minusSeconds(3600), now);
        PreorderProduct atHorizon = fixtures.preorderProduct(now.plus(LOOKAHEAD), now.plusSeconds(7200));
        PreorderProduct pastHorizon = fixtures.preorderProduct(now.plus(LOOKAHEAD).plusMillis(1), now.plusSeconds(7200));

        List<UUID> found = ids(query(500).findOpenOrOpeningSoon(now));

        assertThat(found).contains(atHorizon.productId())
                .doesNotContain(closingNow.productId(), pastHorizon.productId());
    }

    @Test
    void 상한을_넘으면_앞의_것만_주고_넘친_횟수를_센다() {
        Instant now = Instant.now();
        fixtures.preorderProduct(now.minusSeconds(60), now.plusSeconds(3600));
        fixtures.preorderProduct(now.minusSeconds(30), now.plusSeconds(3600));
        CampaignScheduleQuery query = query(1);

        assertThat(query.findOpenOrOpeningSoon(now)).hasSize(1);
        assertThat(query.findOpenOrOpeningSoon(now.plusSeconds(5))).hasSize(1);

        assertThat(meterRegistry.get(CampaignScheduleQuery.TRUNCATED_METRIC).counter().count())
                .as("로그는 10분에 한 번이어도 지표는 매번").isEqualTo(2);
    }

    private CampaignScheduleQuery query(int limit) {
        return new CampaignScheduleQuery(repository, new CampaignProperties(Duration.ofMinutes(10), LOOKAHEAD, limit),
                meterRegistry);
    }

    private static List<UUID> ids(List<CampaignSchedule> schedules) {
        return schedules.stream().map(CampaignSchedule::productId).toList();
    }
}
