package com.grandis.nova.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 발행 카운터({prefix}.publish, 태그 eventType · outcome)와 미발행 현황 게이지 둘({prefix}.unpublished,
 * {prefix}.unpublished.max.attempts). 수집할 때마다 DB 를 묻지 않도록 주기적으로 세어 두고 게이지는 그 값을 읽는다.
 * 여러 인스턴스가 같은 값을 내므로 대시보드에서는 한 인스턴스 값만 본다.
 */
class MicrometerOutboxMetrics implements OutboxMetrics {

    private final MeterRegistry registry;
    private final OutboxStore store;
    private final String publishMetric;
    private final String cleanedMetric;
    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong maxUnpublishedAttempts = new AtomicLong();

    MicrometerOutboxMetrics(MeterRegistry registry, OutboxStore store, String prefix) {
        this.registry = registry;
        this.store = store;
        this.publishMetric = prefix + ".publish";
        this.cleanedMetric = prefix + ".cleaned";
        Gauge.builder(prefix + ".unpublished", unpublished, AtomicLong::get).register(registry);
        Gauge.builder(prefix + ".unpublished.max.attempts", maxUnpublishedAttempts, AtomicLong::get).register(registry);
    }

    @Override
    public void cleaned(int count) {
        registry.counter(cleanedMetric).increment(count);
    }

    @Override
    public void published(String eventType, boolean success) {
        registry.counter(publishMetric, "eventType", eventType, "outcome", success ? "success" : "failure").increment();
    }

    @Override
    public boolean tracksBacklog() {
        return true;
    }

    @Override
    public void refreshBacklog() {
        // 둘 다 센 뒤에 바꾼다 — DB 를 묻는 동안에는 수집이 이전 값 짝을 읽는다
        long count = store.countUnpublished();
        int maxAttempts = store.maxUnpublishedAttempts();
        unpublished.set(count);
        maxUnpublishedAttempts.set(maxAttempts);
    }
}
