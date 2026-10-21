package com.grandis.nova.order.outbox;

import com.grandis.nova.order.outbox.AdminAlertRequested.DrawDelayedMetrics;
import com.grandis.nova.order.outbox.AdminAlertRequested.DrawSettledMetrics;
import com.grandis.nova.order.outbox.AdminAlertRequested.Target;
import com.grandis.nova.order.outbox.AdminAlertRequested.TargetKind;
import com.grandis.nova.order.outbox.AdminAlertRequested.Type;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 종류마다 metrics 모양이 정해져 있다 — 허브가 종류로 칸을 읽으므로 어긋난 메시지는 만들지 못한다. */
class AdminAlertRequestedTest {

    static final UUID DRAW_ID = UUID.fromString("0199a3f2-8a10-7b21-9c32-4d5e6f708192");
    static final Instant CLOSES_AT = Instant.parse("2026-10-20T04:00:00Z");

    @Test
    void 드로우_알림은_회차가_원인이고_대상이다() {
        AdminAlertRequested settled = AdminAlertRequested.drawSettled(DRAW_ID, 0, 3, CLOSES_AT, CLOSES_AT.plusSeconds(60));

        assertThat(settled.eventType()).isEqualTo(OutboundEventType.ADMIN_ALERT_REQUESTED);
        assertThat(settled.aggregateType()).isEqualTo(AggregateType.DRAW_CAMPAIGN);
        assertThat(settled.aggregateId()).isEqualTo(DRAW_ID);
        assertThat(settled.target()).isEqualTo(new Target(TargetKind.DRAW, DRAW_ID.toString()));
        assertThat(settled.metrics()).isEqualTo(new DrawSettledMetrics(0, 3, CLOSES_AT, CLOSES_AT.plusSeconds(60)));

        AdminAlertRequested delayed = AdminAlertRequested.drawDelayed(DRAW_ID, CLOSES_AT, null, 1, 0);
        assertThat(delayed.type()).isEqualTo(Type.DRAW_DELAYED);
        assertThat(delayed.aggregateType()).isEqualTo(AggregateType.DRAW_CAMPAIGN);
        assertThat(delayed.metrics()).isEqualTo(new DrawDelayedMetrics(CLOSES_AT, null, 1, 0));
    }

    @Test
    void 종류와_다른_metrics_는_만들지_못한다() {
        Target target = new Target(TargetKind.DRAW, DRAW_ID.toString());

        assertThatThrownBy(() -> new AdminAlertRequested(DRAW_ID, Type.DRAW_SETTLED, target, new DrawDelayedMetrics(CLOSES_AT, null, 1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminAlertRequested(DRAW_ID, Type.DRAW_DELAYED, target, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminAlertRequested.drawDelayed(null, CLOSES_AT, null, 1, 0)).isInstanceOf(NullPointerException.class);
    }
}
