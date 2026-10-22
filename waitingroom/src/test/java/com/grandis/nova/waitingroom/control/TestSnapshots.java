package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

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
        put(holder, products, meta, 1.0);
    }

    public static void put(SnapshotHolder holder, Map<String, ProductState> products, SnapshotMeta meta, double brakeFactor) {
        holder.replace(new GatewaySnapshot(products, meta, System.currentTimeMillis(), brakeFactor));
    }

    /** 리더가 운영값 · preorder 용량 · 브레이크 창을 실어 낸 재료. */
    public static void put(SnapshotHolder holder, SnapshotMeta meta, double brakeFactor, CapacityStatus capacity,
                           Optional<RelayOutcome> relayWindow) {
        holder.replace(new GatewaySnapshot(Map.of(), meta, System.currentTimeMillis(), brakeFactor, capacity, relayWindow));
    }
}
