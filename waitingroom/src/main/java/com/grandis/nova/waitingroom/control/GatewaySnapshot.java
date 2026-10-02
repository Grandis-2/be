package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;

import java.util.Map;
import java.util.Optional;

/**
 * 리더가 발행한 판정 재료 한 장. 요청 경로는 이것만 보고 판정한다(Redis 를 치지 않는다).
 *
 * @param publishedAtMillis 리더가 재료를 읽은 Redis 시각. 나이는 이 값으로 잰다
 */
public record GatewaySnapshot(Map<String, ProductState> products, SnapshotMeta meta, long publishedAtMillis) {

    public GatewaySnapshot {
        products = Map.copyOf(products);
    }

    public Optional<ProductState> product(String productKey) {
        return Optional.ofNullable(products.get(productKey));
    }
}
