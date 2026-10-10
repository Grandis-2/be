package com.grandis.nova.order.event;

import java.util.List;
import java.util.UUID;

/**
 * DRAW_RESULT_READY 의 payload(worker 가 Mock 의 추첨 결과를 옮긴다 — contracts/draw-entry-sync.md). 회차 id 는 봉투의 aggregateId 로만 온다.
 * 칸이 빠지면 풀 때 실패시킨다(재수신 한도를 넘으면 DLQ).
 *
 * @param winnerEntryIds 당첨 응모 id — 당첨 인원만큼, 겹치지 않게
 */
public record DrawResultReady(List<UUID> winnerEntryIds) {

    public DrawResultReady {
        if (winnerEntryIds == null || winnerEntryIds.isEmpty() || winnerEntryIds.contains(null)) {
            throw new IllegalArgumentException("당첨 응모가 비었다");
        }
        winnerEntryIds = List.copyOf(winnerEntryIds);
    }
}
