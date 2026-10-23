package com.grandis.nova.preorder.scaling;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.OptionalLong;

/**
 * 대기열 관리자가 정한 전체 초당 입장 인원(공유 Redis 의 대기열 운영값). 읽기만 한다 — 쓰기 · 지우기는 대기열만 한다.
 * 감속 장치가 줄인 값이 아니라 관리자가 정한 원래 값이다.
 */
class AdmissionCredit {

    static final String KEY = "wr:settings";
    static final String FIELD = "global-credit";

    private final StringRedisTemplate redis;

    AdmissionCredit(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 없거나 0 이상 정수가 아니면 비어 있다 — 관리자가 정한 적이 없다는 뜻이다. Redis 를 읽지 못하면 예외가 그대로 난다. */
    OptionalLong read() {
        String raw = redis.<String, String>opsForHash().get(KEY, FIELD);
        if (raw == null) {
            return OptionalLong.empty();
        }
        try {
            long credit = Long.parseLong(raw.strip());
            return credit < 0 ? OptionalLong.empty() : OptionalLong.of(credit);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
