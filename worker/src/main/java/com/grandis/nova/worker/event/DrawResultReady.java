package com.grandis.nova.worker.event;

import java.util.List;
import java.util.UUID;

/**
 * 추첨 결과를 order 에 알린다(DRAW_RESULT_READY). 봉투의 aggregateId 는 회차 id 다.
 *
 * @param winnerEntryIds 당첨 응모 id — Mock 이 뽑은 그대로, 정확히 당첨 인원만큼 겹치지 않게
 */
public record DrawResultReady(List<UUID> winnerEntryIds) {

    public static final String EVENT_TYPE = "DRAW_RESULT_READY";
    public static final String AGGREGATE_TYPE = "DRAW_CAMPAIGN";
    public static final String QUEUE = "order-events";

    public DrawResultReady {
        winnerEntryIds = List.copyOf(winnerEntryIds);
    }
}
