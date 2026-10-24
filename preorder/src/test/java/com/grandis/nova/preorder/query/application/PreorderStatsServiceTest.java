package com.grandis.nova.preorder.query.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.CampaignPhase;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.deadletter.DeadLetterWaiting;
import com.grandis.nova.preorder.preorder.PreorderBacklog;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PreorderStatsServiceTest {

    final PreorderStatsCounter counter = mock(PreorderStatsCounter.class);
    final PreorderStatsCache cache = mock(PreorderStatsCache.class);
    final PreorderStatsHistoryStore history = mock(PreorderStatsHistoryStore.class);
    final Campaigns campaigns = mock(Campaigns.class);
    final PreorderStatsService service = new PreorderStatsService(counter, cache,
            new PreorderStatsProperties(Duration.ofSeconds(5), 20, 100), history,
            new PreorderStatsHistoryProperties(Duration.ofSeconds(10), Duration.ofMinutes(30), Duration.ofMinutes(1),
                    100), campaigns, Clock.fixed(Instant.parse("2026-10-25T01:00:00Z"), ZoneOffset.UTC));

    @Test
    void 지난_기록의_분은_1에서_보관_시간까지다() {
        assertThatThrownBy(() -> service.history(null, 0)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.history(null, 31)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        given(history.since(null, Instant.parse("2026-10-25T00:30:00Z"))).willReturn(List.of());

        assertThat(service.history(null, null).stepSeconds()).isEqualTo(10);
    }

    @Test
    void 기록을_읽을_수_없으면_속도만_비우고_현황은_그대로_준다() {
        given(history.since(any(), any())).willThrow(new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE));

        assertThat(service.rates(null)).isNull();
    }

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

    @Test
    void 상품별_현황은_크기를_빼면_기본_크기로_세고_같은_단계_커서_크기로_캐시에_둔다() {
        ProductStatsPage counted = new ProductStatsPage(List.of(), null, Instant.parse("2026-10-13T02:15:30Z"));
        given(cache.findPage(CampaignPhase.OPEN, null, 20)).willReturn(Optional.empty());
        given(counter.countProducts(CampaignPhase.OPEN, PhaseCursor.FIRST_PAGE, 20)).willReturn(counted);

        assertThat(service.findProducts(CampaignPhase.OPEN, " ", null)).isSameAs(counted);

        verify(cache).putPage(CampaignPhase.OPEN, null, 20, counted);
    }

    @Test
    void 상품별_현황의_크기가_범위_밖이거나_커서가_깨졌으면_세지_않고_400_이다() {
        assertThatThrownBy(() -> service.findProducts(CampaignPhase.CLOSED, null, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findProducts(CampaignPhase.CLOSED, null, 101))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findProducts(CampaignPhase.CLOSED, "깨진-커서", 20))
                .isInstanceOf(BusinessException.class);

        verify(counter, never()).countProducts(any(), any(), anyInt());
    }

    static PreorderStats stats(UUID productId) {
        return new PreorderStats(productId, new PreorderBacklog(Map.of(), 0, null, null), Map.of(),
                new DeadLetterWaiting(0, null), Instant.parse("2026-10-13T02:15:30Z"));
    }
}
