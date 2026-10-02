package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.health.contributor.Status;

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
    void 제어_평면을_끈_구성에서는_살아_있다고_본다() {
        ControlLoopHealthIndicator indicator =
                new ControlLoopHealthIndicator(new DefaultListableBeanFactory().getBeanProvider(ControlPlane.class));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
