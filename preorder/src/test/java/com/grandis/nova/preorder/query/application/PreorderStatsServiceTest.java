package com.grandis.nova.preorder.query.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.deadletter.DeadLetterWaiting;
import com.grandis.nova.preorder.preorder.PreorderBacklog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PreorderStatsServiceTest {

    final PreorderStatsCounter counter = mock(PreorderStatsCounter.class);
    final PreorderStatsCache cache = mock(PreorderStatsCache.class);
    final PreorderStatsService service = new PreorderStatsService(counter, cache);

    @Test
    void 캐시에_있으면_세지_않고_그_값을_준다() {
        UUID productId = UUID.randomUUID();
        PreorderStats cached = stats(productId);
        given(cache.find(productId)).willReturn(Optional.of(cached));

        assertThat(service.find(productId)).isSameAs(cached);

        verify(counter, never()).count(any());
    }

    @Test
    void 캐시에_없으면_세고_그_범위로_캐시에_둔다() {
        PreorderStats counted = stats(null);
        given(cache.find(null)).willReturn(Optional.empty());
        given(counter.count(null)).willReturn(counted);

        assertThat(service.find(null)).isSameAs(counted);

        verify(cache).put(null, counted);
    }

    @Test
    void 없는_상품은_캐시에_남기지_않는다() {
        UUID productId = UUID.randomUUID();
        given(cache.find(productId)).willReturn(Optional.empty());
        given(counter.count(productId)).willThrow(new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));

        assertThatThrownBy(() -> service.find(productId)).isInstanceOf(BusinessException.class);

        verify(cache, never()).put(any(), any());
    }

    static PreorderStats stats(UUID productId) {
        return new PreorderStats(productId, new PreorderBacklog(Map.of(), 0, null), Map.of(),
                new DeadLetterWaiting(0, null), Instant.parse("2026-10-13T02:15:30Z"));
    }
}
