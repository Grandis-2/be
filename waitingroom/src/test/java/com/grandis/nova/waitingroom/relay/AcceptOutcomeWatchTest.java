package com.grandis.nova.waitingroom.relay;

import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptOutcomeWatchTest {

    @Test
    void 다시_진입할_때는_지금_더하기_오차_뒤에_시작하는_창이라_새_입장권이_접수_규칙을_통과한다() {
        for (long now = 1_000; now < 1_000 + 2 * AdmissionTicket.WINDOW_SEC; now++) {
            long wait = AcceptOutcomeWatch.retryAfterSeconds(Instant.ofEpochSecond(now));
            long windowStart = (now + wait) / AdmissionTicket.WINDOW_SEC * AdmissionTicket.WINDOW_SEC;

            assertThat(windowStart).as("now=%d", now).isGreaterThan(now + AdmissionTicket.WINDOW_SEC);
            assertThat(wait).isBetween(1L, 2 * AdmissionTicket.WINDOW_SEC);
        }
    }
}
