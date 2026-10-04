package com.grandis.nova.waitingroom.domain.admission;

import java.util.HashMap;
import java.util.Map;

/**
 * 노드 로컬 초 단위 고정 창 리미터. 모델 예산과 노드 예산을 한 번에 함께 차감한다 —
 * 따로 차감하면 뒤엣것이 거절할 때 통과하지 않은 요청이 앞 예산을 깎는다.
 */
public class SecondWindowLimiter {

    /** 키가 요청에서 오므로 상한이 없으면 메모리가 무한히 는다. */
    private final int maxKeys;
    private final Map<String, Long> used = new HashMap<>();
    private long windowSecond = Long.MIN_VALUE;

    public SecondWindowLimiter(int maxKeys) {
        if (maxKeys < 2) {
            throw new IllegalArgumentException("키 상한은 2 이상이어야 한다(모델 + 노드): " + maxKeys);
        }
        this.maxKeys = maxKeys;
    }

    /** 두 예산을 전부-아니면-전무로 획득한다. 실패면 어느 쪽이 부족했는지 돌려준다. */
    public synchronized AcquireResult tryAcquireAll(String productKey, long productCap, String globalKey,
                                                    long globalCap, long epochSecond) {
        if (productKey.equals(globalKey)) {
            throw new IllegalArgumentException("두 예산의 키가 같으면 안 된다: " + productKey);
        }
        rollWindow(epochSecond);
        if (!hasRoom(productKey, productCap)) {
            return AcquireResult.PRODUCT_EXHAUSTED;
        }
        if (!hasRoom(globalKey, globalCap)) {
            return AcquireResult.GLOBAL_EXHAUSTED;
        }
        // 새 키 수를 먼저 센다. 하나씩 보면 마지막 자리 하나를 두 키가 함께 차지해 상한을 넘긴다
        int incoming = (used.containsKey(productKey) ? 0 : 1) + (used.containsKey(globalKey) ? 0 : 1);
        if (used.size() + incoming > maxKeys) {
            return AcquireResult.KEY_SATURATED;
        }
        used.merge(productKey, 1L, Long::sum);
        used.merge(globalKey, 1L, Long::sum);
        return AcquireResult.ACQUIRED;
    }

    /** 지금 들고 있는 키 수. */
    public synchronized int size() {
        return used.size();
    }

    private boolean hasRoom(String key, long cap) {
        return cap > 0 && used.getOrDefault(key, 0L) < cap;
    }

    /** 초가 바뀌면 창을 통째로 버린다. 과거 초가 오면(시계 보정) 현재 창을 날리지 않는다. */
    private void rollWindow(long epochSecond) {
        if (epochSecond <= windowSecond) {
            return;
        }
        windowSecond = epochSecond;
        used.clear();
    }

    /** 획득 결과. 실패 사유마다 대응이 달라 나눈다. */
    public enum AcquireResult {
        ACQUIRED,
        PRODUCT_EXHAUSTED,
        GLOBAL_EXHAUSTED,
        KEY_SATURATED
    }
}
