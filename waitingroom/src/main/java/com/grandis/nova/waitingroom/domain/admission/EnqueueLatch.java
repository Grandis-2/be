package com.grandis.nova.waitingroom.domain.admission;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 줄에 세운 직후 구간을 메운다. 스냅샷은 한 틱 늦어 아직 한산하다고 말하고, 그동안 방금 줄 선 사람을
 * 신규 유입이 추월한다. 노드 로컬이다. 조회 · 표식 · 상한 관리를 한 잠금으로 묶어 크기가 상한을 넘지 않는다.
 */
public final class EnqueueLatch {

    private final Map<String, Long> marked = new HashMap<>();
    private final int maxKeys;
    private final long ttlSec;

    private EnqueueLatch(int maxKeys, long ttlSec) {
        if (maxKeys < 1) {
            throw new IllegalArgumentException("키 상한은 양수여야 한다: " + maxKeys);
        }
        if (ttlSec < 1) {
            throw new IllegalArgumentException("래치 수명은 양수여야 한다: " + ttlSec);
        }
        this.maxKeys = maxKeys;
        this.ttlSec = ttlSec;
    }

    /** 주어진 기간을 반드시 덮는 래치. 초로 자른 시각을 재므로 올림한 뒤 한 초를 더한다. */
    public static EnqueueLatch covering(int maxKeys, Duration atLeast) {
        if (atLeast == null || atLeast.isNegative() || atLeast.isZero()) {
            throw new IllegalArgumentException("덮을 기간은 양수여야 한다: " + atLeast);
        }
        long seconds = atLeast.toSeconds();
        long rounded = atLeast.minusSeconds(seconds).isZero() ? seconds : seconds + 1;
        return new EnqueueLatch(maxKeys, rounded + 1);
    }

    /**
     * 이 모델을 방금 줄로 보냈다. 살아 있는 표식은 시각을 고치지 않는다 — 고치면 유입이 이어지는 동안 영영 안 풀린다.
     * 새 키가 들어갈 자리가 없으면 먼저 통째로 비운다(골라 버리는 순회를 요청 경로에 안 붙인다).
     */
    public synchronized void mark(String productKey, long epochSecond) {
        Long at = marked.get(productKey);
        if (at != null && alive(at, epochSecond)) {
            return;
        }
        if (at == null && marked.size() >= maxKeys) {
            marked.clear();
        }
        marked.put(productKey, epochSecond);
    }

    /** 아직 걸려 있는가. 수명만큼은 뒤로 간 시계도 걸린 것으로 본다 — 거기서 풀면 그 방향이 추월이다. */
    public synchronized boolean latched(String productKey, long epochSecond) {
        Long at = marked.get(productKey);
        if (at == null) {
            return false;
        }
        if (alive(at, epochSecond)) {
            return true;
        }
        marked.remove(productKey);
        return false;
    }

    /** 수명만큼은 뒤로 간 시계도 살아 있는 것으로 본다. */
    private boolean alive(long at, long epochSecond) {
        long age = epochSecond - at;
        return age < ttlSec && age > -ttlSec;
    }

    public synchronized int size() {
        return marked.size();
    }
}
