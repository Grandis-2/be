package com.grandis.nova.common.redis.cache;

import java.time.Duration;

/**
 * 읽기 캐시의 지표 — 쓰는 서비스가 구현한다. common:redis 는 지표 라이브러리에 기대지 않는다.
 * 미리 갱신을 넣기 전후로 요청의 "바로 받음 · 직접 읽음 · 기다림" 비율과 기다린 시간을 비교하려고 둔다. 요청마다의 결과({@link #served})와
 * 갱신 작업마다의 결과({@link #refreshed})는 따로 센다 — 섞으면 결과를 다 더해도 요청 수가 아니라 비율의 분모가 틀린다.
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

        @Override
        public void refreshed(String cache, RefreshResult result) {
        }
    };

    /** 요청 하나의 결과 — 요청마다 정확히 하나. */
    void served(String cache, Outcome outcome);

    /** 같은 키의 진행 중 조회를 기다린 시간 — {@link Outcome#WAIT} 요청마다(시간 초과 포함). */
    void waited(String cache, Duration waited);

    /** 미리 갱신 작업 하나의 결과 — 맡기려다 거절된 것도 하나로 센다. */
    void refreshed(String cache, RefreshResult result);

    enum Outcome {
        /** 들고 있던 값을 바로 줬다(미리 갱신을 맡기지 않았다 — 맡길 때가 아니거나, 이미 맡겼거나, 거절됐거나). */
        HIT,
        /** 들고 있던 값을 바로 줬고, 이 요청이 미리 갱신을 실제로 맡겼다. */
        HIT_REFRESH,
        /** 이 요청이 직접 DB 를 읽었다. */
        LOAD,
        /** 같은 키의 진행 중 조회(직접 읽기 또는 미리 갱신)를 기다렸다. */
        WAIT
    }

    enum RefreshResult {
        /** 새 값을 읽어 넣었다. */
        STORED,
        /** 읽었지만 넣지 못했다(넣지 않는 값 · 저장 상한 · 남은 시간 없음 등) — 들고 있던 값이 만료될 때까지 다시 맡기지 않는다. */
        NOT_STORED,
        /** 돌 차례가 왔을 때 세대가 바뀌었거나, 이미 더 새 값이 있거나, 누가 읽는 중이라 읽지 않았다. */
        SKIPPED_NEWER,
        /** 읽기(원천 loader)가 실패했다 — 만료를 늘리지 않고, 그 값이 만료될 때까지 그 키는 다시 맡기지 않는다. */
        FAILED,
        /** 돌 차례에 Redis 에서 지금 세대나 값을 못 읽어(장애 · 차단기) 원천을 읽지 않았다 — 넣지도 못할 값을 묻지 않게. 그 키는 막아 두지 않아 다음 히트가 다시 맡긴다. */
        SKIPPED_UNAVAILABLE,
        /** 실행기가 차서(또는 다른 이유로) 맡기지 못했다 — 그 값이 만료될 때까지 다시 맡기지 않고, 만료 때 요청이 직접 읽는다. */
        REJECTED
    }
}
