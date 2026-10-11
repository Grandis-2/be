package com.grandis.nova.order.order.vo;

import java.util.Optional;
import java.util.UUID;

/** 밖에서 받은 주문 id(orders.id). 응답이 내보내는 모양(소문자 표준형 UUID)만 받는다. */
public final class OrderIds {

    private OrderIds() {
    }

    /** 경로로 받은 주문 id. 형식이 틀리면 없는 주문과 같다 — 400 으로 나눠 봐야 호출자가 얻는 정보가 없다(404). */
    public static Optional<UUID> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
