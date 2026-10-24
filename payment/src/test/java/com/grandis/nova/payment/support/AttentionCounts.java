package com.grandis.nova.payment.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * 사람 확인 지표(payment.recovery.attention)의 증가분. 통합 테스트는 레지스트리를 공유하므로 테스트 시작 때 값을 찍어 두고 차이만 본다.
 * 키는 "유형/대상/사유"(예: CAPTURE/ORDER/TIMED_OUT)다.
 */
public final class AttentionCounts {

    private final MeterRegistry registry;
    private final Map<String, Double> before;

    private AttentionCounts(MeterRegistry registry) {
        this.registry = registry;
        this.before = read(registry);
    }

    public static AttentionCounts snapshot(MeterRegistry registry) {
        return new AttentionCounts(registry);
    }

    public static String key(String type, String target, String reason) {
        return type + "/" + target + "/" + reason;
    }

    /** 찍은 뒤 늘어난 시계열만. */
    public Map<String, Double> delta() {
        Map<String, Double> changed = new HashMap<>();
        read(registry).forEach((key, now) -> {
            double grown = now - before.getOrDefault(key, 0.0);
            if (grown != 0) {
                changed.put(key, grown);
            }
        });
        return changed;
    }

    private static Map<String, Double> read(MeterRegistry registry) {
        Map<String, Double> counts = new HashMap<>();
        for (Counter counter : registry.find("payment.recovery.attention").counters()) {
            counts.put(key(counter.getId().getTag("type"), counter.getId().getTag("target"), counter.getId().getTag("reason")),
                    counter.count());
        }
        return counts;
    }
}
