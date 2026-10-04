package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.redis.RedisKeys;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 운영값(Redis SETTINGS 해시) — global-credit · max-wait-sec · cap:{모델}. 없거나 깨진 값은 기본값으로 둔다 —
 * 운영값 하나가 깨졌다고 배분 전체가 멈추면 안 된다. 관리자 API 가 쓸 때 검증한다.
 */
public record OperationalSettings(long globalCredit, MaxWait maxWait, Map<String, Long> caps) {

    public static final String GLOBAL_CREDIT = "global-credit";
    public static final String MAX_WAIT_SEC = "max-wait-sec";

    public static OperationalSettings from(Map<String, String> raw, long defaultGlobalCredit) {
        long globalCredit = positiveOr(raw.get(GLOBAL_CREDIT), defaultGlobalCredit, true);
        long maxWaitSec = positiveOr(raw.get(MAX_WAIT_SEC), -1, false);
        Map<String, Long> caps = new HashMap<>();
        raw.forEach((field, value) -> {
            if (field.startsWith("cap:")) {
                String productKey = field.substring(4);
                long cap = positiveOr(value, -1, false);
                if (RedisKeys.validProductKey(productKey) && cap > 0) {
                    caps.put(productKey, cap);
                }
            }
        });
        return new OperationalSettings(globalCredit,
                maxWaitSec > 0 ? MaxWait.of(Duration.ofSeconds(maxWaitSec)) : MaxWait.unlimited(), Map.copyOf(caps));
    }

    /** 리더가 이 전역 속도 값을 받아들이는가(0 포함). 아니면 기본값을 쓴다. */
    public static boolean acceptsGlobalCredit(String value) {
        return value != null && positiveOr(value, -1, true) >= 0;
    }

    /** 리더가 이 최대 대기 시간 값을 받아들이는가. 아니면 제한 없음이다. */
    public static boolean acceptsMaxWait(String value) {
        return value != null && positiveOr(value, -1, false) > 0;
    }

    public long capOf(String productKey) {
        return caps.getOrDefault(productKey, ProductState.UNLIMITED_CAP);
    }

    private static long positiveOr(String value, long fallback, boolean allowZero) {
        if (value == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 || (allowZero && parsed == 0) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
