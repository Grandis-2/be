package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.PreorderFact;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시험 설정은 기록기를 끄므로 같은 부품으로 직접 만들어 부른다. 주기 표식은 부를 때마다 지워 같은 주기에도 기록하게 한다.
 * 다른 시험의 회차도 접수 중이므로 이 시험의 상품은 가장 먼저 마감하게 만들어 기록 대상 상한 안에 들게 한다.
 */
@PreorderIntegrationTest
class PreorderStatsSamplerTest {

    @Autowired
    PreorderStatsCounter counter;

    @Autowired
    PreorderStatsHistoryStore store;

    @Autowired
    PreorderStatsHistoryProperties properties;

    @Autowired
    PreorderStatsService statsService;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    PreorderStatsSampler sampler;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        sampler = new PreorderStatsSampler(counter, store, properties, Clock.systemUTC());
    }

    @Test
    void 전체와_접수_중인_상품의_예약_수를_쌓고_그_기록으로_속도를_잰다() {
        Instant now = Instant.now();
        PreorderProduct open = fixtures.preorderProduct(now.minusSeconds(3600), now.plusSeconds(120));
        PreorderProduct closed = fixtures.preorderProduct(now.minusSeconds(7200), now.minusSeconds(3600));
        accepts.accept(fixtures.customer(), open);
        sampleNow();
        accepts.accept(fixtures.customer(), open);
        register(accepts.accept(fixtures.customer(), open).preorder().id());
        sampleNow();

        List<StatsSample> samples = statsService.history(open.productId(), 30).samples();
        assertThat(samples).hasSize(2);
        assertThat(samples.getFirst().received()).isEqualTo(1);
        StatsSample last = samples.getLast();
        assertThat(last.received()).isEqualTo(3);
        assertThat(last.pendingSync()).isEqualTo(2);
        assertThat(last.registered()).isEqualTo(1);
        assertThat(last.reserved()).isZero();
        assertThat(statsService.history(closed.productId(), 30).samples()).as("마감한 상품은 쌓지 않는다").isEmpty();
        assertThat(statsService.history(null, 30).samples()).hasSizeGreaterThanOrEqualTo(2);

        StatsRates rates = statsService.rates(open.productId());
        assertThat(rates).isNotNull();
        assertThat(rates.receivedPerSec()).isPositive();
        assertThat(rates.registeredPerSec()).isPositive();
        assertThat(rates.backlogPerMin()).isPositive();
        assertThat(statsService.rates(closed.productId())).isNull();
    }

    @Test
    void 한_주기는_한_곳만_맡는다() {
        clearTicks();
        Instant at = Instant.now();

        assertThat(store.claim(at)).isTrue();
        assertThat(store.claim(at)).isFalse();
        assertThat(store.claim(at.plus(properties.interval()))).isTrue();
    }

    private void sampleNow() {
        clearTicks();
        assertThat(sampler.sample()).isTrue();
    }

    private void clearTicks() {
        Set<String> ticks = redis.keys(PreorderStatsHistoryStore.KEY_PREFIX + "tick:*");
        if (!ticks.isEmpty()) {
            redis.delete(ticks);
        }
    }

    private void register(UUID preorderId) {
        transactionTemplate.executeWithoutResult(status ->
                ledger.fire(preorderId, new PreorderFact.RegisterConfirmed("EXT-" + ShopFixtures.unique())));
    }
}
