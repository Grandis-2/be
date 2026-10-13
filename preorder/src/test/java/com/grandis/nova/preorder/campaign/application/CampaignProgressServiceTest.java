package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.campaign.domain.PreorderSaleStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CampaignProgressServiceTest {

    static final Instant OPENS_AT = Instant.parse("2026-10-14T01:00:00Z");
    static final Instant CLOSES_AT = Instant.parse("2026-10-21T01:00:00Z");
    static final ShipmentBatchSnapshot FIRST_BATCH = new ShipmentBatchSnapshot(UUID.randomUUID(), 1, 1, 100L,
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7));

    final CampaignProgressReader reader = mock(CampaignProgressReader.class);
    final Clock clock = mock(Clock.class);
    final UUID productId = UUID.randomUUID();

    @Test
    void 캐시_시간_안에는_다시_읽지_않고_상품마다_따로_둔다() {
        UUID other = UUID.randomUUID();
        given(clock.instant()).willReturn(OPENS_AT);
        given(reader.read(productId)).willReturn(source(productId));
        given(reader.read(other)).willReturn(source(other));
        CampaignProgressService service = service(Duration.ofHours(1));

        assertThat(service.find(productId)).isEqualTo(service.find(productId));
        assertThat(service.find(other).productId()).isEqualTo(other);

        verify(reader, times(1)).read(productId);
        verify(reader, times(1)).read(other);
    }

    /** 상태까지 캐시하면 오픈 순간에 캐시 시간만큼 "오픈 전"으로 보인다. */
    @Test
    void 캐시된_일정이어도_접수_상태와_차수_안내는_물을_때의_시각으로_정한다() {
        given(reader.read(productId)).willReturn(source(productId));
        CampaignProgressService service = service(Duration.ofHours(1));

        given(clock.instant()).willReturn(OPENS_AT.minusMillis(1));
        CampaignProgress beforeOpen = service.find(productId);
        given(clock.instant()).willReturn(OPENS_AT);
        CampaignProgress open = service.find(productId);
        given(clock.instant()).willReturn(CLOSES_AT);
        CampaignProgress closed = service.find(productId);

        assertThat(beforeOpen.saleStatus()).isEqualTo(PreorderSaleStatus.BEFORE_OPEN);
        assertThat(beforeOpen.nextBatch()).isNull();
        assertThat(open.saleStatus()).isEqualTo(PreorderSaleStatus.OPEN);
        assertThat(open.nextBatch().batchNumber()).isEqualTo(1);
        assertThat(closed.saleStatus()).isEqualTo(PreorderSaleStatus.CLOSED);
        assertThat(closed.nextBatch()).isNull();
        verify(reader, times(1)).read(productId);
    }

    @Test
    void 캐시_시간이_0_이면_매번_읽는다() {
        given(clock.instant()).willReturn(OPENS_AT);
        given(reader.read(productId)).willReturn(source(productId));
        CampaignProgressService service = service(Duration.ZERO);

        service.find(productId);
        service.find(productId);

        verify(reader, times(2)).read(productId);
    }

    @Test
    void 없는_상품은_캐시에_남기지_않는다() {
        given(reader.read(productId)).willThrow(new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        CampaignProgressService service = service(Duration.ofHours(1));

        assertThatThrownBy(() -> service.find(productId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.find(productId)).isInstanceOf(BusinessException.class);

        verify(reader, times(2)).read(productId);
    }

    private CampaignProgressService service(Duration cacheTtl) {
        return new CampaignProgressService(reader, new CampaignProgressProperties(cacheTtl, 200), clock);
    }

    private static CampaignProgressSource source(UUID productId) {
        return new CampaignProgressSource(new CampaignSchedule(productId, OPENS_AT, CLOSES_AT), FIRST_BATCH,
                OPENS_AT.minusSeconds(3));
    }
}
