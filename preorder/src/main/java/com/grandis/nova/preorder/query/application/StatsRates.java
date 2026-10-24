package com.grandis.nova.preorder.query.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 기록의 처음과 끝 차이로 잰 속도. 서버가 재므로 화면을 새로 고쳐도, 관리자마다 달리 보지 않는다.
 *
 * @param receivedPerSec   초당 접수
 * @param registeredPerSec 초당 외부 등록 대기에서 빠져나간 수(등록을 마친 것, 등록 전에 취소된 것 포함)
 * @param backlogPerMin    분당 외부 등록 대기 증감. 양수면 밀린다
 * @param windowSeconds    잰 구간
 */
public record StatsRates(double receivedPerSec, double registeredPerSec, double backlogPerMin, long windowSeconds) {

    /**
     * 마지막 기록에서 window 안의 기록으로 잰다. 기록이 둘보다 적거나, 마지막 기록이 두 주기보다 오래됐으면(기록이 멈춤) null.
     *
     * @param samples 시각 순
     */
    static StatsRates of(List<StatsSample> samples, Instant now, Duration interval, Duration window) {
        if (samples.size() < 2) {
            return null;
        }
        StatsSample last = samples.getLast();
        if (last.at().isBefore(now.minus(interval.multipliedBy(2)))) {
            return null;
        }
        Instant from = last.at().minus(window);
        StatsSample first = samples.stream().filter(sample -> !sample.at().isBefore(from)).findFirst().orElseThrow();
        long millis = Duration.between(first.at(), last.at()).toMillis();
        if (millis <= 0) {
            return null;
        }
        double seconds = millis / 1000.0;
        long received = last.received() - first.received();
        long backlog = last.pendingSync() - first.pendingSync();
        return new StatsRates(received / seconds, (received - backlog) / seconds, backlog / seconds * 60,
                Math.round(seconds));
    }
}
