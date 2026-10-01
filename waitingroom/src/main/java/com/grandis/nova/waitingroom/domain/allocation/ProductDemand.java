package com.grandis.nova.waitingroom.domain.allocation;

/**
 * 이 모델이 이번 틱에 받고 싶은 초당 입장 인원. 모델 상한이 천장이다 — 회차 잠금이 받을 수 있는 것보다
 * 많이 입장시키면 접수가 줄지어 막히고, 그 몫은 다른 모델이 못 쓴 채 버려진다. 접수 중이 아니면 0 이다.
 */
public record ProductDemand(String productKey, long waiting, long cap, boolean accepting) {

    public ProductDemand {
        if (productKey == null || productKey.isBlank()) {
            throw new IllegalArgumentException("productKey 는 필수다");
        }
        if (waiting < 0) {
            throw new IllegalArgumentException("waiting 은 0 이상이어야 한다: " + waiting);
        }
        if (cap < 1) {
            throw new IllegalArgumentException("cap 은 1 이상이어야 한다: " + cap);
        }
    }

    /** 오픈 전 · 마감 뒤에 입장시키면 접수에서 거절될 사람만 내보내고 다른 모델의 몫을 갉아먹는다. */
    public long want() {
        return accepting ? Math.min(waiting, cap) : 0;
    }

    /** 줄이 없으면 몫을 안 받는다. 한산한 모델의 credit 이 0 인 까닭이다. */
    public boolean isActive() {
        return want() > 0;
    }
}
