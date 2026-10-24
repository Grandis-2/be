package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 주기마다 전체와 접수 중 상품들의 예약 수를 기록한다. 태스크마다 돌지만 주기를 먼저 맡은 한 곳만 센다.
 * 한 번 실패하면 그 주기의 기록만 빠진다 — 속도는 남은 기록으로 잰다. 기본으로 켜고 enabled=false 로 끈다.
 */
@Component
@ConditionalOnProperty(prefix = "nova.preorder-stats.history", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class PreorderStatsSampler {

    private static final Logger log = LoggerFactory.getLogger(PreorderStatsSampler.class);

    private final PreorderStatsCounter counter;
    private final PreorderStatsHistoryStore store;
    private final PreorderStatsHistoryProperties properties;
    private final Clock clock;

    PreorderStatsSampler(PreorderStatsCounter counter, PreorderStatsHistoryStore store,
                         PreorderStatsHistoryProperties properties, Clock clock) {
        this.counter = counter;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${nova.preorder-stats.history.interval:10s}",
            initialDelayString = "${nova.preorder-stats.history.interval:10s}")
    void run() {
        try {
            sample();
        } catch (DataAccessException e) {
            log.warn("예약 수를 기록하지 못했다 — 이 주기만 빠진다", e);
        }
    }

    /** @return 이 주기를 맡아 기록했으면 true */
    boolean sample() {
        if (!store.claim(clock.instant())) {
            return false;
        }
        SampledCounts sampled = counter.countForHistory(properties.maxProducts());
        store.append(null, StatsSample.of(sampled.at(), sampled.total()));
        for (Map.Entry<UUID, Map<PreorderStatus, Long>> product : sampled.products().entrySet()) {
            store.append(product.getKey(), StatsSample.of(sampled.at(), product.getValue()));
        }
        return true;
    }
}
