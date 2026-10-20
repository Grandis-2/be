package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.job.JobType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 1분에 5건이면 3분 멈춘다. */
@ExtendWith(OutputCaptureExtension.class)
class RejectGuardTest {

    final MutableClock clock = new MutableClock();
    final RejectGuard guard = new RejectGuard(JobType.REGISTER, Settings.rejectGuard(), clock);

    @Test
    void 거절이_기준만큼_이어지면_받기를_멈추고_경보를_남긴다(CapturedOutput output) {
        for (int i = 0; i < 4; i++) {
            guard.record("INVALID_REQUEST");
        }
        assertThat(guard.blocked()).isFalse();

        guard.record("INVALID_REQUEST");

        assertThat(guard.blocked()).isTrue();
        assertThat(guard.remaining()).isEqualTo(Duration.ofMinutes(3));
        assertThat(output.getOut()).contains("ERROR").contains("받기를 멈춘다 jobType=REGISTER reason=INVALID_REQUEST");
    }

    @Test
    void 멈춘_시간이_지나면_다시_받고_또_이어지면_다시_멈춘다() {
        block();

        clock.advance(Duration.ofSeconds(61));
        assertThat(guard.blocked()).as("세는 창(1분)이 아니라 멈춤 시간(3분)을 따른다").isTrue();
        clock.advance(Duration.ofMinutes(2));
        assertThat(guard.blocked()).isFalse();
        assertThat(guard.remaining()).isZero();

        block();
        assertThat(guard.blocked()).isTrue();
    }

    /** 멈추기 전에 받아 둔 건이 멈춘 동안 거절돼도, 다시 받자마자 그것만으로 또 멈추지 않는다. */
    @Test
    void 멈춘_동안의_거절은_세지_않는다() {
        block();
        for (int i = 0; i < 10; i++) {
            guard.record("INVALID_REQUEST");
        }

        clock.advance(Duration.ofMinutes(3));
        guard.record("INVALID_REQUEST");

        assertThat(guard.blocked()).isFalse();
    }

    @Test
    void 드문드문_오는_거절로는_멈추지_않는다() {
        for (int i = 0; i < 20; i++) {
            guard.record("INVALID_REQUEST");
            clock.advance(Duration.ofSeconds(20));
        }

        assertThat(guard.blocked()).isFalse();
    }

    private void block() {
        for (int i = 0; i < 5; i++) {
            guard.record("MISCONFIGURED");
        }
    }
}
