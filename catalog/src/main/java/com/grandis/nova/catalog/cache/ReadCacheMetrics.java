package com.grandis.nova.catalog.cache;

import com.grandis.nova.common.redis.cache.ReadCacheEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 읽기 캐시 지표 — nova.read.cache.requests{cache, outcome}(요청마다 하나: 바로 받음 · 바로 받고 갱신 맡김 · 직접 읽음 · 기다림),
 * nova.read.cache.wait{cache}(같은 키의 진행 중 조회를 기다린 시간), nova.read.cache.refresh{cache, result}(갱신 작업마다 하나).
 * 미리 갱신 전후로 기다림의 비율 · 시간과 직접 읽음(DB 조회)의 수를 비교한다 — 캐시를 켠 동안 requests 의 outcome 을 다 더하면 캐시를 거친
 * 요청 수다(캐시를 끄면 지표가 남지 않는다). refresh 의 skipped_unavailable 은 Redis 를 못 읽어 건너뛴 갱신이라, Redis 간헐 실패 동안에는
 * 그만큼 바로 받고 갱신 맡김(HIT_REFRESH)이 다시 맡긴 것으로 더 센다.
 */
@Component
public class ReadCacheMetrics implements ReadCacheEvents {

    private final MeterRegistry registry;

    public ReadCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void served(String cache, Outcome outcome) {
        Counter.builder("nova.read.cache.requests").tag("cache", cache).tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).register(registry).increment();
    }

    @Override
    public void refreshed(String cache, RefreshResult result) {
        Counter.builder("nova.read.cache.refresh").tag("cache", cache).tag("result", result.name().toLowerCase(Locale.ROOT)).register(registry).increment();
    }

    @Override
    public void waited(String cache, Duration waited) {
        Timer.builder("nova.read.cache.wait").tag("cache", cache).publishPercentileHistogram().register(registry).record(waited);
    }
}
