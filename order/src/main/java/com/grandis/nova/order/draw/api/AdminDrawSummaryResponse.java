package com.grandis.nova.order.draw.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.order.draw.DrawQueryService;
import com.grandis.nova.order.draw.domain.model.DrawProgress;
import com.grandis.nova.order.draw.domain.model.DrawStage;

import java.time.Instant;

/**
 * 관리자 회차 목록 한 줄. 회차에 진행 요약이 붙는다. 응모 상태별 수 · 추첨 요청 시각은 회차별 진행 API 에 있다.
 *
 * @param entryCount 결제를 마쳤거나 확인 중인 응모 수 — 결제 대기는 응모를 마치지 않아 세지 않는다
 * @param winners    당첨 수
 * @param settledAt  결과를 반영한 시각
 * @param stage      진행 단계({@link DrawStage})
 */
public record AdminDrawSummaryResponse(
        @JsonUnwrapped DrawResponse draw,
        long entryCount,
        long winners,
        Instant settledAt,
        DrawStage stage
) {

    static AdminDrawSummaryResponse of(DrawQueryService.WithProgress row, Instant now) {
        DrawProgress progress = row.progress();
        return new AdminDrawSummaryResponse(DrawResponse.of(row.campaign(), now), progress.paidEntries() + progress.authorizingEntries(),
                progress.winners(), progress.settledAt(), DrawStage.of(row.campaign(), progress.settledAt(), now));
    }
}
