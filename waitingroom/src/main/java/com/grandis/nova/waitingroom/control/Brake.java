package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 관측 브레이크 — 리더가 회차마다 노드들이 본 접수 전달 결과(5xx · 실패 · 느림)로 전역 속도에 곱할 배율을 정한다.
 * 줄이기만 하고, 올리는 상한은 운영값이다(배율 1). 노드별 누적의 차이로 세어 하트비트를 놓치거나 겹쳐도 두 번 세지 않는다.
 */
@Component
class Brake {

    private static final Logger log = LoggerFactory.getLogger(Brake.class);
    /** 배율은 천분율로 맞춘다 — 0.1 을 더해 가다 0.9999999999999999 가 되지 않게. */
    private static final double RESOLUTION = 1000;

    private final BrakeProperties properties;
    private final ControlMetrics metrics;
    /** 최근 window 동안의 회차별 차이. 회차가 밀려도 시간으로 자른다 — 회차 수로 자르면 창이 늘어 판정이 늦다. */
    private final Deque<Sample> window = new ArrayDeque<>();
    private final Map<String, RelayOutcome> lastTotals = new HashMap<>();
    /** 응답에서 잠깐 빠진 노드의 마지막 누적을 몇 회차 남겨 둔다 — 돌아왔을 때 빠진 동안의 결과를 잃지 않게. */
    private final Map<String, Integer> missingRounds = new HashMap<>();
    private static final int RETAIN_MISSING_ROUNDS = 10;
    private double factor = 1.0;
    private long fence;
    private Instant lastCut = Instant.MIN;
    private Instant lastStep = Instant.MIN;
    private Instant healthySince;

    Brake(BrakeProperties properties, ControlMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    synchronized boolean needsAdopt(long currentFence) {
        return properties.enabled() && currentFence != fence;
    }

    /**
     * 새 임기 — 직전 리더가 발행한 배율에서 이어 간다. 직전 리더가 방금 줄였을 수 있으니 이 시각부터 유지 기간을 다시 센다.
     * 노드별 누적은 처음 보는 것으로 두어 첫 회차에 뜬 뒤 전부를 한꺼번에 세지 않는다.
     */
    synchronized void adopt(long currentFence, double adoptedFactor, Instant now) {
        fence = currentFence;
        factor = quantize(adoptedFactor);
        lastCut = now;
        lastStep = now;
        window.clear();
        lastTotals.clear();
        missingRounds.clear();
        healthySince = null;
    }

    /** 이번 회차의 배율. 리더의 배분 회차에서만 회차마다 한 번 부른다. */
    synchronized double next(Map<String, RelayOutcome> totals, Instant now) {
        if (!properties.enabled()) {
            return 1.0;
        }
        window.addLast(new Sample(now, delta(totals)));
        Instant since = now.minus(properties.window());
        while (!window.isEmpty() && !window.peekFirst().at().isAfter(since)) {
            window.removeFirst();
        }
        long relayed = window.stream().mapToLong(sample -> sample.outcome().relayed()).sum();
        long bad = window.stream().mapToLong(sample -> sample.outcome().bad()).sum();
        double ratio = relayed == 0 ? 0 : (double) bad / relayed;
        if (relayed >= properties.minSamples() && ratio >= properties.badRatio()) {
            healthySince = null;
            if (!now.isBefore(lastCut.plus(properties.hold())) && factor > properties.floor()) {
                double before = factor;
                factor = quantize(Math.max(properties.floor(), factor * properties.cut()));
                lastCut = now;
                metrics.brakeChanged("CUT");
                log.warn("접수 전달이 나빠 입장 속도를 줄인다 — 나쁜 응답 {}/{} 배율 {} → {}", bad, relayed, before, factor);
            }
        } else if (ratio < properties.recoverRatio()) {
            healthySince = healthySince == null ? now : healthySince;
            boolean recovered = !now.isBefore(healthySince.plus(properties.recoverAfter()));
            if (factor < 1.0 && recovered && !now.isBefore(lastStep.plus(properties.stepEvery()))) {
                double before = factor;
                factor = quantize(Math.min(1.0, factor + properties.step()));
                lastStep = now;
                metrics.brakeChanged("RECOVER");
                log.info("접수 전달이 회복돼 입장 속도를 되돌린다 — 배율 {} → {}", before, factor);
            }
        } else {
            healthySince = null;
        }
        return factor;
    }

    /** 노드별 누적의 차이를 더한다. 처음 보는 노드는 0, 누적이 줄었으면(노드 재시작) 지금 누적을 차이로 본다. */
    private RelayOutcome delta(Map<String, RelayOutcome> totals) {
        long relayed = 0;
        long bad = 0;
        for (Map.Entry<String, RelayOutcome> entry : totals.entrySet()) {
            RelayOutcome current = entry.getValue();
            RelayOutcome previous = lastTotals.put(entry.getKey(), current);
            missingRounds.remove(entry.getKey());
            if (previous == null) {
                continue;
            }
            boolean restarted = current.relayed() < previous.relayed() || current.bad() < previous.bad();
            relayed += restarted ? current.relayed() : current.relayed() - previous.relayed();
            bad += restarted ? current.bad() : current.bad() - previous.bad();
        }
        lastTotals.keySet().removeIf(node -> !totals.containsKey(node)
                && missingRounds.merge(node, 1, Integer::sum) > RETAIN_MISSING_ROUNDS);
        missingRounds.keySet().retainAll(lastTotals.keySet());
        return new RelayOutcome(relayed, bad);
    }

    private static double quantize(double value) {
        return Math.round(value * RESOLUTION) / RESOLUTION;
    }

    /** 운영값에 배율을 곱한다. 운영값이 0(일시 정지)이 아니면 1 아래로는 내리지 않는다 — 브레이크가 입장을 멈추지는 않는다. */
    static long apply(long operationalCredit, double factor) {
        if (operationalCredit <= 0) {
            return 0;
        }
        return Math.max(1, (long) Math.floor(operationalCredit * factor));
    }

    private record Sample(Instant at, RelayOutcome outcome) {
    }
}
