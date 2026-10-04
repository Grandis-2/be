package com.grandis.nova.waitingroom.entry;

import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;

/** 예상 대기 시간을 잴 입장 속도. */
final class EtaCredit {

    private EtaCredit() {
    }

    /**
     * 모델 몫을 모르면(급증 직후 판정 재료가 아직 한산을 말할 때 0) 전역 속도와 모델 상한 중 작은 쪽으로 잰다 —
     * 모른다고 가장 긴 조회 간격을 주면 이미 입장한 사람이 30초 뒤에야 안다. 전역 0(일시 정지)이면 모름 그대로다.
     */
    static double of(ProductState state, SnapshotMeta meta) {
        return state.credit() > 0 ? state.credit() : Math.min(meta.globalCredit(), state.cap());
    }
}
