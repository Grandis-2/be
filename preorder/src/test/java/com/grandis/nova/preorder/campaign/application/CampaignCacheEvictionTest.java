package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.ProductListing;
import com.grandis.nova.preorder.campaign.ProductStatus;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchPlan;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchPlan.Line;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.CatalogStubs;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

/**
 * 회차를 바꾼 트랜잭션이 커밋되면 캐시가 바로 새 값을 보이고, 롤백이면 그대로다. 다른 인스턴스는 Redis 채널로 비운다.
 * DB 를 비우기 없이 바꾼 값(시험 도우미)이 안 보이면 캐시가 DB 를 다시 읽지 않은 것이다.
 */
@PreorderIntegrationTest
class CampaignCacheEvictionTest {

    @Autowired
    CampaignCache cache;

    @Autowired
    Campaigns campaigns;

    @Autowired
    PreorderCampaignAdminService adminService;

    @Autowired
    CampaignCacheInvalidation invalidation;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    StringRedisTemplate redis;

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
    void 읽은_뒤에는_DB_를_다시_읽지_않는다() {
        PreorderProduct product = fixtures.openPreorderProduct();
        assertThat(find(product).visible()).isTrue();

        fixtures.listing(product.productId(), "상품", "ACTIVE", false);

        assertThat(find(product).visible()).as("비우기 전에는 캐시 값").isTrue();
    }

    @Test
    void 조기_마감이_커밋되면_바로_새_일정이_보인다() {
        PreorderProduct product = fixtures.openPreorderProduct();
        Instant closesAt = find(product).schedule().closesAt();

        campaigns.closeNow(product.productId(), Instant.now());

        assertThat(find(product).schedule().closesAt()).isBefore(closesAt);
    }

    /** 공개 여부 · 판매 상태도 캐시에 있다 — 판매 상태만 바뀌어 회차 변경 이벤트가 없어도 비운다. */
    @Test
    void 상품_사본을_반영하면_판매_상태만_바뀌어도_비운다() {
        PreorderProduct product = fixtures.openPreorderProduct();
        assertThat(find(product).productStatus()).isNotEqualTo(ProductStatus.PAUSED);

        campaigns.applyListing(product.productId(), new ProductListing("상품", ProductStatus.PAUSED, true, 10));

        assertThat(find(product).productStatus()).isEqualTo(ProductStatus.PAUSED);
    }

    @Test
    void 차수를_바꾸면_바로_새_차수가_보인다() {
        Instant now = Instant.now();
        PreorderProduct product = fixtures.preorderProduct(now.plus(Duration.ofDays(2)), now.plus(Duration.ofDays(3)));
        assertThat(find(product).batches()).hasSize(2);

        adminService.replaceBatches(product.productId(), new ShipmentBatchPlan(List.of(
                new Line(1, 1, null, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 7)))));

        assertThat(find(product).batches()).hasSize(1);
    }

    @Test
    void 롤백되면_비우지_않는다() {
        PreorderProduct product = fixtures.openPreorderProduct();
        find(product);
        fixtures.listing(product.productId(), "상품", "ACTIVE", false);

        transactionTemplate.executeWithoutResult(status -> {
            invalidation.evictAfterCommit(product.productId());
            status.setRollbackOnly();
        });

        assertThat(find(product).visible()).isTrue();
    }

    @Test
    void 다른_인스턴스가_보낸_알림을_받으면_비운다() {
        PreorderProduct product = fixtures.openPreorderProduct();
        find(product);
        fixtures.listing(product.productId(), "상품", "ACTIVE", false);

        redis.convertAndSend(CampaignCacheInvalidation.CHANNEL, product.productId().toString());

        await().atMost(Duration.ofSeconds(5)).until(() -> !find(product).visible());
    }

    @Test
    void 회차를_만들면_회차_없음을_비운다() {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        assertThat(cache.find(productId)).isEmpty();
        Instant opensAt = Instant.now().plus(Duration.ofDays(2));

        given(catalogClient.getProduct(productId)).willReturn(
                CatalogStubs.preorderProduct(productId, CatalogStubs.activeOption(UUID.randomUUID())));

        adminService.upsertCampaign(productId, opensAt, opensAt.plus(Duration.ofDays(1)));

        assertThat(cache.find(productId)).isPresent();
    }

    private CachedCampaign find(PreorderProduct product) {
        return cache.find(product.productId()).orElseThrow();
    }
}
