package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.draw.domain.model.DrawProgress;

import java.time.Instant;

/**
 * 관리자 회차 진행. 마감 뒤 authorizingEntries 가 0 이 되면 추첨한다 — 결제 대기(awaitingEntries)는 추첨 대상이 아니다.
 *
 * @param drawRequestedAt Mock 에 추첨을 요청한 시각. 결제 완료가 당첨 인원 이하면(전원 당첨) 비어 있다
 * @param settledAt       결과를 반영한 시각(당첨 주문 · 재고)
 */
public record DrawProgressResponse(long paidEntries, long authorizingEntries, long awaitingEntries, long winners, Instant drawRequestedAt,
                                   Instant settledAt) {

    static DrawProgressResponse of(DrawProgress p) {
        return new DrawProgressResponse(p.paidEntries(), p.authorizingEntries(), p.awaitingEntries(), p.winners(), p.drawRequestedAt(),
                p.settledAt());
    }
}
