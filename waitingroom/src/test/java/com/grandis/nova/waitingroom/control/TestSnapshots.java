package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;

import java.time.Clock;
import java.util.Map;

/** 제어 평면 없이 판정 재료를 바로 넣는다(요청 경로 시험용). */
public final class TestSnapshots {

    private TestSnapshots() {
    }

    /** 아직 판정 재료를 한 번도 받지 않은 노드. */
    public static SnapshotHolder emptyHolder() {
        return new SnapshotHolder(new RedisClock(Clock.systemUTC()), new ControlPlaneProperties(null, null, null, null,
                null, null, null, null));
    }

    public static void put(SnapshotHolder holder, Map<String, ProductState> products, SnapshotMeta meta) {
        holder.replace(new GatewaySnapshot(products, meta, System.currentTimeMillis()));
    }
}
