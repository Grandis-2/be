package com.grandis.nova.preorder.integration.catalog;

import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.DependencyGuards;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Redis 로 알리기에 실패하면 뒤에서 다시 알리고, 끝내 실패하면 지표를 남긴다. 이벤트 처리는 막지 않는다. */
class CatalogCacheInvalidationRetryTest {

    static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-7000-8000-000000000007");

    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final CatalogClient client = mock(CatalogClient.class);
    final CatalogReader reader = new CatalogReader(client, DependencyGuards.passThrough(), Runnable::run,
            Clock.systemUTC());
    final CatalogCacheInvalidation invalidation = new CatalogCacheInvalidation(reader, redis, Runnable::run,
            List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO), meterRegistry);

    @Test
    void 알리기에_한_번_실패해도_다시_알려_성공하면_실패로_세지_않는다() {
        cache();
        given(redis.convertAndSend(any(), any()))
                .willThrow(new RedisConnectionFailureException("down"))
                .willReturn(1L);

        invalidation.evictEverywhere(PRODUCT_ID);

        assertThat(reader.cache().getIfPresent(PRODUCT_ID)).isNull();
        verify(redis, times(2)).convertAndSend(CatalogCacheInvalidation.CHANNEL, PRODUCT_ID.toString());
        assertThat(failures()).isZero();
    }

    @Test
    void 끝내_알리지_못하면_이_인스턴스만_비우고_실패를_세며_예외는_올리지_않는다() {
        cache();
        willThrow(new RedisConnectionFailureException("down")).given(redis).convertAndSend(any(), any());

        invalidation.evictEverywhere(PRODUCT_ID);

        assertThat(reader.cache().getIfPresent(PRODUCT_ID)).isNull();
        verify(redis, times(4)).convertAndSend(CatalogCacheInvalidation.CHANNEL, PRODUCT_ID.toString());
        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void 재시도가_가득_차_거절되면_실패로_세고_예외는_올리지_않는다() {
        cache();
        willThrow(new RedisConnectionFailureException("down")).given(redis).convertAndSend(any(), any());
        CatalogCacheInvalidation full = new CatalogCacheInvalidation(reader, redis, task -> {
            throw new TaskRejectedException("가득 참");
        }, List.of(Duration.ZERO), meterRegistry);

        full.evictEverywhere(PRODUCT_ID);

        assertThat(reader.cache().getIfPresent(PRODUCT_ID)).isNull();
        verify(redis, times(1)).convertAndSend(CatalogCacheInvalidation.CHANNEL, PRODUCT_ID.toString());
        assertThat(failures()).isEqualTo(1);
    }

    private void cache() {
        given(client.getProduct(PRODUCT_ID)).willReturn(
                CatalogStubs.preorderProduct(PRODUCT_ID,
                        CatalogStubs.activeOption(UUID.fromString("00000000-0000-7000-8000-000000000070"))));
        reader.findProduct(PRODUCT_ID);
        assertThat(reader.cache().getIfPresent(PRODUCT_ID)).isNotNull();
    }

    private double failures() {
        return meterRegistry.counter(CatalogCacheInvalidation.PUBLISH_FAILURE_METRIC).count();
    }
}
