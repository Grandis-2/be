package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 리더가 발행한 판정 재료 한 장. 요청 경로는 이것만 보고 판정한다(Redis 를 치지 않는다).
 *
 * @param publishedAtMillis 리더가 재료를 읽은 Redis 시각. 나이는 이 값으로 잰다
 * @param brakeFactor       운영값에 곱한 브레이크 배율(1 = 걸리지 않음). meta 의 전역 속도는 곱한 뒤 값이다
 * @param capacity          전역 속도를 정할 때 본 운영값과 preorder 용량
 * @param relayWindow       브레이크가 본 최근 창의 접수 전달 결과. 브레이크가 꺼졌거나 옛 재료면 빈 값
 */
public record GatewaySnapshot(Map<String, ProductState> products, SnapshotMeta meta, long publishedAtMillis,
                              double brakeFactor, CapacityStatus capacity, Optional<RelayOutcome> relayWindow) {

    public GatewaySnapshot {
        products = Map.copyOf(products);
        Objects.requireNonNull(capacity, "capacity 는 필수다. 모르면 CapacityStatus.MISSING");
        Objects.requireNonNull(relayWindow, "relayWindow 는 필수다. 모르면 Optional.empty()");
    }

    public GatewaySnapshot(Map<String, ProductState> products, SnapshotMeta meta, long publishedAtMillis,
                           double brakeFactor, CapacityStatus capacity) {
        this(products, meta, publishedAtMillis, brakeFactor, capacity, Optional.empty());
    }

    /** 운영값 · 용량을 모르는 재료. 적용된 전역 속도는 브레이크 뒤 값이라 운영값으로 쓰지 않는다. */
    public GatewaySnapshot(Map<String, ProductState> products, SnapshotMeta meta, long publishedAtMillis,
                           double brakeFactor) {
        this(products, meta, publishedAtMillis, brakeFactor, CapacityStatus.MISSING);
    }

    public GatewaySnapshot(Map<String, ProductState> products, SnapshotMeta meta, long publishedAtMillis) {
        this(products, meta, publishedAtMillis, 1.0);
    }

    public Optional<ProductState> product(String productKey) {
        return Optional.ofNullable(products.get(productKey));
    }
}
