package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.ProductListing;
import com.grandis.nova.preorder.campaign.ProductStatus;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 상품마다 한 번만 읽고, "회차 없음" 은 따로 두며, 비우는 동안 읽은 값은 남기지 않는다. */
class CampaignCacheTest {

    static final Instant OPENS_AT = Instant.parse("2026-11-01T01:00:00Z");

    final PreorderCampaignRepository campaigns = mock(PreorderCampaignRepository.class);
    final ShipmentBatchRepository batches = mock(ShipmentBatchRepository.class);
    final MutableClock clock = new MutableClock(OPENS_AT.minusSeconds(10));

    /** 접수 잠금 안에서 쓰는 차수는 오픈 뒤에 읽어 둔 것만 — 오픈 전에 읽은 값은 비우기를 놓쳤으면 교체 전 차수일 수 있다. */
    @Test
    void 차수는_그_시각_뒤에_읽어_둔_것만_주고_읽지_않는다() {
        UUID productId = UUID.randomUUID();
        given(campaigns.lookup(productId)).willReturn(Optional.of(campaign(productId)));
        CampaignCache cache = cache(10, 10);

        assertThat(cache.batchesLoadedSince(productId, OPENS_AT)).as("가진 값이 없으면 읽지 않는다").isEmpty();
        cache.find(productId);
        assertThat(cache.batchesLoadedSince(productId, OPENS_AT)).as("오픈 전에 읽은 값").isEmpty();

        cache.evict(productId);
        clock.now = OPENS_AT;
        cache.find(productId);

        assertThat(cache.batchesLoadedSince(productId, OPENS_AT)).isPresent();
        verify(campaigns, times(2)).lookup(productId);
    }

    @Test
    void 같은_상품은_수명_안에_다시_읽지_않는다() {
        UUID productId = UUID.randomUUID();
        given(campaigns.lookup(productId)).willReturn(Optional.of(campaign(productId)));
        CampaignCache cache = cache(10, 10);

        assertThat(cache.find(productId)).isPresent();
        assertThat(cache.find(productId)).isPresent();

        verify(campaigns, times(1)).lookup(productId);
    }

    /** 무작위 id 로 물어도 진짜 회차가 밀려나지 않는다 — "없음" 은 따로 세고 따로 둔다. */
    @Test
    void 회차_없음은_따로_두어_진짜_회차를_밀어내지_않는다() {
        UUID productId = UUID.randomUUID();
        given(campaigns.lookup(productId)).willReturn(Optional.of(campaign(productId)));
        CampaignCache cache = cache(1, 5);
        cache.find(productId);

        IntStream.range(0, 50).forEach(i -> assertThat(cache.find(UUID.randomUUID())).isEmpty());

        assertThat(cache.find(productId)).isPresent();
        verify(campaigns, times(1)).lookup(productId);
    }

    @Test
    void 회차_없음도_수명_안에_다시_읽지_않고_비우면_다시_읽는다() {
        UUID productId = UUID.randomUUID();
        given(campaigns.lookup(productId)).willReturn(Optional.empty());
        CampaignCache cache = cache(10, 10);

        assertThat(cache.find(productId)).isEmpty();
        assertThat(cache.find(productId)).isEmpty();
        verify(campaigns, times(1)).lookup(productId);

        cache.evict(productId);
        given(campaigns.lookup(productId)).willReturn(Optional.of(campaign(productId)));
        assertThat(cache.find(productId)).isPresent();
    }

    @Test
    void 여럿을_물으면_캐시에_없는_것만_한_번에_읽고_없는_회차는_빠진다() {
        UUID cached = UUID.randomUUID();
        UUID loaded = UUID.randomUUID();
        UUID absent = UUID.randomUUID();
        given(campaigns.lookup(cached)).willReturn(Optional.of(campaign(cached)));
        given(campaigns.lookupAll(anyCollection())).willReturn(List.of(campaign(loaded)));
        CampaignCache cache = cache(10, 10);
        cache.find(cached);

        List<CachedCampaign> found = cache.findAll(List.of(cached, loaded, absent, loaded));

        assertThat(found).extracting(campaign -> campaign.schedule().productId()).containsExactly(cached, loaded);
        verify(campaigns).lookupAll(argThat(ids -> List.of(loaded, absent).equals(ids)));
        assertThat(cache.findAll(List.of(loaded, absent))).hasSize(1);
        verify(campaigns, times(1)).lookupAll(anyCollection());
    }

    /** 읽는 사이 커밋되고 비웠다면 읽은 값은 비우기 전의 값일 수 있다 — 두지 않는다. */
    @Test
    void 여럿을_읽는_동안_비우면_읽은_값을_두지_않는다() {
        UUID productId = UUID.randomUUID();
        CampaignCache cache = cache(10, 10);
        willAnswer(invocation -> {
            cache.evict(productId);
            return List.of(campaign(productId));
        }).given(campaigns).lookupAll(anyCollection());

        assertThat(cache.findAll(List.of(productId))).hasSize(1);
        cache.findAll(List.of(productId));

        verify(campaigns, times(2)).lookupAll(anyCollection());
    }

    private CampaignCache cache(int maxProducts, int missingMax) {
        return new CampaignCache(campaigns, batches,
                new CampaignCacheProperties(Duration.ofMinutes(1), maxProducts, Duration.ofMinutes(1), missingMax),
                clock);
    }

    /** 오픈 전에 읽은 회차는 오픈 시각에 버려, 오픈 뒤 첫 조회가 다시 읽는다 — 잠금 안의 차수 고르기가 캐시를 쓰게. */
    @Test
    void 오픈_전에_읽은_회차는_오픈_시각에_다시_읽는다() throws InterruptedException {
        UUID productId = UUID.randomUUID();
        given(campaigns.lookup(productId)).willReturn(Optional.of(campaign(productId)));
        clock.now = OPENS_AT.minusMillis(50);
        CampaignCache cache = cache(10, 10);
        cache.find(productId);

        Thread.sleep(200);
        clock.now = OPENS_AT;
        cache.find(productId);

        verify(campaigns, times(2)).lookup(productId);
        assertThat(cache.batchesLoadedSince(productId, OPENS_AT)).isPresent();
    }

    static class MutableClock extends Clock {

        volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static PreorderCampaign campaign(UUID productId) {
        return new PreorderCampaign(productId, OPENS_AT, OPENS_AT.plusSeconds(3600),
                new ProductListing("상품", ProductStatus.ACTIVE, true, 1));
    }
}
