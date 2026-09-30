package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@PreorderIntegrationTest
class PreorderCampaignRepositoryTest {

    @Autowired
    PreorderCampaignRepository campaigns;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 동시에_순번을_받아도_중복과_공백이_없다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        int requests = 20;

        List<Outcome<Long>> outcomes = Concurrently.run(requests, i -> () -> transactionTemplate.execute(status ->
                campaigns.findForUpdate(product.productId()).orElseThrow().issueQueuePosition()));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().map(Outcome::value).toList()).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, requests).boxed().toList());
        assertThat(campaigns.findById(product.productId()).orElseThrow().getNextQueuePosition())
                .isEqualTo(requests + 1);
    }

    @Test
    void 잠금이_풀릴_때까지_같은_회차의_다음_트랜잭션이_기다린다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        CountDownLatch firstLocked = new CountDownLatch(1);
        long holdMillis = 500;

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> first = executor.submit(lockAndHold(product.productId(), firstLocked, holdMillis));
            firstLocked.await(10, TimeUnit.SECONDS);
            long secondStarted = System.nanoTime();
            Future<Long> second = executor.submit(() -> transactionTemplate.execute(status -> {
                campaigns.findForUpdate(product.productId()).orElseThrow();
                return System.nanoTime();
            }));

            long firstReleased = first.get(10, TimeUnit.SECONDS);
            long secondLocked = second.get(10, TimeUnit.SECONDS);

            assertThat(secondLocked).isGreaterThanOrEqualTo(firstReleased);
            assertThat(TimeUnit.NANOSECONDS.toMillis(secondLocked - secondStarted))
                    .isGreaterThanOrEqualTo(holdMillis / 2);
        }
    }

    @Test
    void 오픈_시각부터_마감_시각_전까지_접수한다() {
        Instant opensAt = Instant.parse("2026-10-01T01:00:00Z");
        Instant closesAt = Instant.parse("2026-10-08T01:00:00Z");
        PreorderProduct product = fixtures.preorderProduct(opensAt, closesAt);

        PreorderCampaign campaign = campaigns.findById(product.productId()).orElseThrow();

        assertThat(campaign.isAccepting(opensAt.minusNanos(1000))).isFalse();
        assertThat(campaign.isAccepting(opensAt)).isTrue();
        assertThat(campaign.isAccepting(closesAt.minusNanos(1000))).isTrue();
        assertThat(campaign.isAccepting(closesAt)).isFalse();
        assertThat(campaign.isOpened(opensAt)).isTrue();
        assertThat(campaign.isOpened(opensAt.minusNanos(1000))).isFalse();
    }

    /** 회차를 잠근 채 잠시 들고 있다가 커밋한다. 커밋 직전 시각을 돌려준다. */
    private Callable<Long> lockAndHold(Long productId, CountDownLatch locked, long holdMillis) {
        return () -> transactionTemplate.execute(status -> {
            campaigns.findForUpdate(productId).orElseThrow();
            locked.countDown();
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return System.nanoTime();
        });
    }
}
