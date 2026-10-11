package com.grandis.nova.waitingroom.support;

/** 시험용 id. 운영처럼 UUID 소문자 표준 표기이고, 번호가 같으면 같은 값이다. */
public final class TestIds {

    private TestIds() {
    }

    public static String uuid(int n) {
        return "00000000-0000-7000-8000-%012d".formatted(n);
    }

    public static String productKey(int n) {
        return uuid(n);
    }

    public static String customerId(int n) {
        return uuid(n);
    }
}
