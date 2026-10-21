package com.grandis.nova.order.draw;

import com.grandis.nova.order.draw.domain.model.DrawProgress;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 반영 지연 검사는 회차마다 따로다 — 한 회차의 실패(잠금 · 그 밖의 예외)가 뒤 회차의 알림을 막지 않는다. */
class DrawCloserTest {

    final DrawCampaignStore campaigns = mock(DrawCampaignStore.class);
    final DrawSettlement settlement = mock(DrawSettlement.class);
    final DrawCloser closer = new DrawCloser(campaigns, settlement, Clock.systemUTC());

    @Test
    @DisplayName("앞 회차가 잠금 실패 · 예외로 끝나도 뒤 회차는 검사한다")
    void oneFailedDrawDoesNotStopTheRest() {
        UUID lockFailed = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        UUID stalled = UUID.randomUUID();
        when(campaigns.findUnsettled(any(), any(), anyInt())).thenReturn(List.of(lockFailed, broken, stalled));
        when(settlement.reportStalled(lockFailed)).thenThrow(new PessimisticLockingFailureException("교착"));
        when(settlement.reportStalled(broken)).thenThrow(new IllegalStateException("회차가 없다"));
        when(settlement.reportStalled(stalled)).thenReturn(Optional.of(new DrawProgress(0, 1, 0, 0, null, null)));

        closer.checkStalled();

        verify(settlement).reportStalled(stalled);
    }
}
