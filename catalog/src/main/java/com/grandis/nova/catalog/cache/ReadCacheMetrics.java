package com.grandis.nova.catalog.cache;

import com.grandis.nova.common.redis.cache.ReadCacheEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 읽기 캐시 지표 — nova.read.cache.requests{cache, outcome}(요청마다 바로 받음 · 직접 읽음 · 기다림 · 미리 갱신의 결과)와
 * nova.read.cache.wait{cache}(같은 키의 진행 중 조회를 기다린 시간). 미리 갱신 전후로 기다림의 비율 · 시간과 직접 읽음(DB 조회)의 수를 비교한다.
 */
@Component
public class ReadCacheMetrics implements ReadCacheEvents {

    private final MeterRegistry registry;

    public ReadCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void served(String cache, Outcome outcome) {
        Counter.builder("nova.read.cache.requests").tag("cache", cache).tag("outcome", outcome.name().toLowerCase()).register(registry).increment();
    }

    @Override
    public void waited(String cache, Duration waited) {
        Timer.builder("nova.read.cache.wait").tag("cache", cache).publishPercentileHistogram().register(registry).record(waited);
    }
}
