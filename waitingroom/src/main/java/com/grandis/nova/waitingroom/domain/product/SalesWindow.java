package com.grandis.nova.waitingroom.domain.product;

import java.time.Instant;
import java.util.Objects;

/**
 * 모델의 접수 기간. 오픈 시각 이상 · 마감 시각 미만에만 받는다(preorder 회차와 같은 경계).
 * 요청마다 노드가 직접 시각과 비교한다 — 리더 틱을 기다리면 오픈 순간이 늦게 반영된다.
 */
public record SalesWindow(Instant opensAt, Instant closesAt) {

    public SalesWindow {
        Objects.requireNonNull(opensAt, "opensAt 은 필수다");
        Objects.requireNonNull(closesAt, "closesAt 은 필수다");
        if (!closesAt.isAfter(opensAt)) {
            throw new IllegalArgumentException("closesAt 은 opensAt 보다 뒤여야 한다: %s ~ %s".formatted(opensAt, closesAt));
        }
    }

    public SalesPhase phaseAt(Instant now) {
        if (now.isBefore(opensAt)) {
            return SalesPhase.BEFORE_OPEN;
        }
        return now.isBefore(closesAt) ? SalesPhase.OPEN : SalesPhase.CLOSED;
    }
}
