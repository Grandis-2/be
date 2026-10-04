package com.grandis.nova.preorder.integration.catalog;

import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 캐시는 인스턴스마다 따로라, 다른 인스턴스가 Redis 채널로 보낸 비우기 알림을 받아 비운다. */
@PreorderIntegrationTest
class CatalogCacheInvalidationTest {

    @Autowired
    CatalogReader catalogReader;

    @Autowired
    CatalogCacheInvalidation invalidation;

    @Autowired
    StringRedisTemplate redis;

    @MockitoBean
    CatalogClient catalogClient;

    @Test
    void 다른_인스턴스가_보낸_알림을_받으면_그_상품을_비운다() {
        Long productId = cachedProduct();

        redis.convertAndSend(CatalogCacheInvalidation.CHANNEL, productId.toString());

        await().atMost(Duration.ofSeconds(5)).until(() -> catalogReader.cache().getIfPresent(productId) == null);
    }

    @Test
    void 비우기는_이_인스턴스를_바로_비우고_채널로_알린다() {
        Long productId = cachedProduct();
        Long other = cachedProduct();

        invalidation.evictEverywhere(productId);

        assertThat(catalogReader.cache().getIfPresent(productId)).isNull();
        assertThat(catalogReader.cache().getIfPresent(other)).as("다른 상품은 그대로").isNotNull();
    }

    @Test
    void 읽을_수_없는_알림은_버리고_구독은_계속된다() {
        Long productId = cachedProduct();

        redis.convertAndSend(CatalogCacheInvalidation.CHANNEL, "not-a-number");
        redis.convertAndSend(CatalogCacheInvalidation.CHANNEL, productId.toString());

        await().atMost(Duration.ofSeconds(5)).until(() -> catalogReader.cache().getIfPresent(productId) == null);
    }

    private Long cachedProduct() {
        Long productId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(1L));
        catalogReader.findProduct(productId);
        assertThat(catalogReader.cache().getIfPresent(productId)).isNotNull();
        return productId;
    }
}
