package com.grandis.nova.order.support;

import java.util.UUID;

/** DB 를 쓰지 않는 시험의 고정 id. 번호로 서로 구별해 읽기 쉽게 한다 — id(7) 은 늘 같은 UUID 다. */
public final class TestIds {

    private TestIds() {
    }

    public static UUID id(long number) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(number));
    }
}
