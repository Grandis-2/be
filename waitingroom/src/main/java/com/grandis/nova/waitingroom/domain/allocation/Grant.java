package com.grandis.nova.waitingroom.domain.allocation;

/** 배분 결과. 인원이 아니라 초당 속도다 — 인원으로 주면 노드마다 쓰는 시점이 갈려 같은 초에 몰린다. */
public record Grant(String productKey, long credit) {

    public Grant {
        if (productKey == null || productKey.isBlank()) {
            throw new IllegalArgumentException("productKey 는 필수다");
        }
        if (credit < 0) {
            throw new IllegalArgumentException("credit 은 0 이상이어야 한다: " + credit);
        }
    }
}
