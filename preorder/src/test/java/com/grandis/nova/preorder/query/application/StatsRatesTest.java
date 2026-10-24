package com.grandis.nova.preorder.query.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatsRatesTest {

    final Instant now = Instant.parse("2026-10-25T01:00:00Z");
    final Duration interval = Duration.ofSeconds(10);
    final Duration window = Duration.ofMinutes(1);

    @Test
    void 구간_안의_처음과_끝_기록으로_잰다() {
        List<StatsSample> samples = List.of(
                sample(90, 0, 0),
                sample(60, 100, 10),
                sample(30, 130, 25),
                sample(0, 160, 40));

        StatsRates rates = StatsRates.of(samples, now, interval, window);

        assertThat(rates.receivedPerSec()).isEqualTo(1.0);
        assertThat(rates.registeredPerSec()).isEqualTo(0.5);
        assertThat(rates.backlogPerMin()).isEqualTo(30.0);
        assertThat(rates.windowSeconds()).isEqualTo(60);
    }

    @Test
    void 기록이_둘보다_적거나_마지막_기록이_두_주기보다_오래됐으면_비어_있다() {
        assertThat(StatsRates.of(List.of(sample(0, 10, 1)), now, interval, window)).isNull();
        assertThat(StatsRates.of(List.of(sample(40, 0, 0), sample(21, 10, 1)), now, interval, window)).isNull();
        assertThat(StatsRates.of(List.of(sample(40, 0, 0), sample(20, 10, 1)), now, interval, window)).isNotNull();
    }

    private StatsSample sample(long secondsAgo, long received, long pendingSync) {
        return new StatsSample(now.minusSeconds(secondsAgo), received, pendingSync, 0, 0);
    }
}
