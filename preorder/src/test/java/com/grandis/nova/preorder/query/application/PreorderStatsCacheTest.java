package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.deadletter.DeadLetterWaiting;
import com.grandis.nova.preorder.preorder.PreorderBacklog;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 실제 Redis 에 넣었다 꺼내 본다. 시험마다 새 상품 id 를 키로 쓴다. */
@PreorderIntegrationTest
class PreorderStatsCacheTest {

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void 넣은_값을_그대로_꺼내고_캐시_시간이_지나면_사라지게_둔다() {
        UUID productId = UUID.randomUUID();
        PreorderStats stats = new PreorderStats(productId,
                new PreorderBacklog(Map.of(PreorderStatus.PENDING_SYNC, 3L, PreorderStatus.CANCELED, 0L), 2,
                        Instant.parse("2026-10-13T01:02:11.123456Z"), Instant.parse("2026-10-13T01:05:00.654321Z")),
                Map.of(SyncJobType.REGISTER, Map.of(SyncJobStatus.DEAD_LETTER, 1L)),
                new DeadLetterWaiting(4, null), Instant.parse("2026-10-13T02:15:30Z"));
        PreorderStatsCache cache = cache(redis, Duration.ofSeconds(5));

        assertThat(cache.find(productId)).isEmpty();
        cache.put(productId, stats);

        assertThat(cache.find(productId)).contains(stats);
        assertThat(cache.find(UUID.randomUUID())).as("범위마다 따로 둔다").isEmpty();
        assertThat(redis.getExpire(PreorderStatsCache.key(productId), TimeUnit.MILLISECONDS)).isBetween(1L, 5000L);
    }

    @Test
    void 읽을_수_없는_값은_없는_것으로_본다() {
        UUID productId = UUID.randomUUID();
        redis.opsForValue().set(PreorderStatsCache.key(productId), "{\"preorders\":\"옛 모양\"}", Duration.ofSeconds(5));

        assertThat(cache(redis, Duration.ofSeconds(5)).find(productId)).isEmpty();
    }

    @Test
    void 캐시_시간이_0_이면_Redis_를_쓰지_않는다() {
        StringRedisTemplate unused = mock(StringRedisTemplate.class);
        PreorderStatsCache cache = cache(unused, Duration.ZERO);

        cache.put(null, PreorderStatsServiceTest.stats(null));

        assertThat(cache.find(null)).isEmpty();
        verify(unused, never()).opsForValue();
    }

    @Test
    void Redis_를_쓸_수_없으면_없는_것으로_보고_쓰기도_넘어간다() {
        StringRedisTemplate down = mock(StringRedisTemplate.class);
        given(down.opsForValue()).willThrow(new RedisConnectionFailureException("down"));
        PreorderStatsCache cache = cache(down, Duration.ofSeconds(5));

        assertThat(cache.find(null)).isEmpty();
        cache.put(null, PreorderStatsServiceTest.stats(null));
    }

    private PreorderStatsCache cache(StringRedisTemplate template, Duration ttl) {
        return new PreorderStatsCache(template, jsonMapper, new PreorderStatsProperties(ttl));
    }
}
