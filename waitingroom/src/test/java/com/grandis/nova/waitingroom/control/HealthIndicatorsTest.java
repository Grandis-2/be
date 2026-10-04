package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthIndicatorsTest {

    @Test
    void 판정_재료를_한_번이라도_받아야_준비된_것이다() {
        SnapshotHolder holder = TestSnapshots.emptyHolder();
        SnapshotHealthIndicator indicator = new SnapshotHealthIndicator(holder);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        TestSnapshots.put(holder, Map.of(), new SnapshotMeta(1, 1, MaxWait.unlimited()));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void 제어_평면이_돌지_않는_구성에서는_살아_있다고_본다() {
        assertThat(new ControlLoopHealthIndicator(new LoopBeats(() -> 0)).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void 루프_하나라도_시한을_넘겨_멈추면_DOWN_이고_돌아오면_UP_이다() {
        long[] now = {0};
        LoopBeats beats = new LoopBeats(() -> now[0]);
        ControlLoopHealthIndicator indicator = new ControlLoopHealthIndicator(beats);
        beats.start(List.of("leader", "tick"));

        now[0] = ControlLoopHealthIndicator.STALL.toNanos() - 1;
        beats.beat("leader");
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        now[0] = ControlLoopHealthIndicator.STALL.toNanos();
        assertThat(indicator.health().getStatus()).as("tick 만 멈췄다").isEqualTo(Status.DOWN);

        beats.beat("tick");
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        beats.stop();
        now[0] += ControlLoopHealthIndicator.STALL.toNanos() * 2;
        assertThat(indicator.health().getStatus()).as("멈춘 뒤에는 보지 않는다").isEqualTo(Status.UP);
    }
}
