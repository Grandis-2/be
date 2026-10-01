package com.grandis.nova.waitingroom.domain.product;

import java.util.Objects;

/**
 * 모델별 상태와 함께 오는 전역 값 — 전 모델 합산 초당 입장 인원, 살아 있는 게이트웨이 수(노드 몫의 분모),
 * 받아 줄 최대 대기 시간(운영값).
 */
public record SnapshotMeta(long globalCredit, int gatewayCount, MaxWait maxWait) {

    public SnapshotMeta {
        if (globalCredit < 0) {
            throw new IllegalArgumentException("globalCredit 은 음수가 될 수 없다: " + globalCredit);
        }
        Objects.requireNonNull(maxWait, "maxWait 은 필수다. 제한 없음은 MaxWait.unlimited()");
    }

    /** 분모로 쓸 노드 수. 0 이하는 관측 실패지 노드가 없다는 뜻이 아니다 — 이 코드가 도는 노드가 있다. */
    public int effectiveGatewayCount() {
        return Math.max(1, gatewayCount);
    }

    /** 이 노드가 초당 감당할 양. */
    public long globalCapPerNode() {
        return globalCredit / effectiveGatewayCount();
    }
}
