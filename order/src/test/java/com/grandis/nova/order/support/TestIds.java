package com.grandis.nova.order.support;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** 시험의 id. {@link #id} 는 DB 를 쓰지 않는 시험의 고정값, {@link #next} 는 DB 에 넣는 행의 새 값이다. */
public final class TestIds {

    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());

    private TestIds() {
    }

    /** 번호로 서로 구별해 읽기 쉽게 한다 — id(7) 은 늘 같은 UUID 다. */
    public static UUID id(long number) {
        return UUID.fromString("00000000-0000-7000-8000-%012d".formatted(number));
    }

    /**
     * 운영 id(v7)처럼 만든 순서대로 커지는 새 id. 앞 48비트가 늘어나는 값이라 기본 정렬({@link UUID#compareTo})과
     * DB 의 BINARY(16) 정렬이 같다 — 무작위 UUID 는 둘이 갈라 시험이 DB 순서를 따로 계산해야 한다.
     */
    public static UUID next() {
        long msb = (SEQUENCE.getAndIncrement() << 16) | 0x7000L | ThreadLocalRandom.current().nextLong(0x1000L);
        long lsb = 0x8000000000000000L | (ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(msb, lsb);
    }
}
