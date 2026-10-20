package com.grandis.nova.common.redis.cache;

import java.time.Duration;

/**
 * 읽기 캐시가 요청 하나를 어떻게 답했는가 — 지표를 다는 쪽(쓰는 서비스)이 구현한다. common:redis 는 지표 라이브러리에 기대지 않는다.
 * 미리 갱신을 넣기 전후로 "바로 받음 · 직접 읽음 · 기다림" 의 비율과 기다린 시간을 비교하려고 둔다.
 */
public interface ReadCacheEvents {

    /** 아무것도 하지 않는다 — 지표를 달지 않는 곳(시험 · 끈 캐시). */
    ReadCacheEvents NONE = new ReadCacheEvents() {
        @Override
        public void served(String cache, Outcome outcome) {
        }

        @Override
        public void waited(String cache, Duration waited) {
        }
    };

    /** 요청 하나의 결과. */
    void served(String cache, Outcome outcome);

    /** 같은 키의 진행 중 조회를 기다린 시간 — {@link Outcome#WAIT} 요청마다(시간 초과 포함). */
    void waited(String cache, Duration waited);

    enum Outcome {
        /** 들고 있던 값을 바로 줬다. */
        HIT,
        /** 들고 있던 값을 바로 줬고, 만료가 가까워 미리 갱신을 맡겼다. */
        HIT_REFRESH,
        /** 이 요청이 직접 DB 를 읽었다. */
        LOAD,
        /** 같은 키의 진행 중 조회(직접 읽기 또는 미리 갱신)를 기다렸다. */
        WAIT,
        /** 미리 갱신이 끝나 새 값을 넣었다(요청이 아니라 갱신 작업 하나). */
        REFRESHED,
        /** 미리 갱신을 맡기지 못했다 — 갱신 실행기가 찼다. 값은 만료 때 요청이 직접 읽는다. */
        REFRESH_REJECTED,
        /** 미리 갱신의 조회가 실패했다 — 만료를 늘리지 않는다. */
        REFRESH_FAILED
    }
}
