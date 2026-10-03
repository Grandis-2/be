package com.grandis.nova.waitingroom.control;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * 제어 평면 지표 — 리더 여부, 판정 재료 나이, 게이트웨이 수, 모델별 대기 · 몫, 입장 누적, 루프 실패.
 * 모델별 값은 판정 재료를 받을 때마다 통째로 갈아 끼운다(끝난 모델이 남지 않게).
 */
@Component
public class ControlMetrics {

    private final MeterRegistry registry;
    private final MultiGauge waiting;
    private final MultiGauge credit;

    public ControlMetrics(MeterRegistry registry, Leadership leadership, SnapshotHolder holder) {
        this.registry = registry;
        Gauge.builder("waitingroom.leader", leadership, current -> current.isLeader() ? 1 : 0)
                .description("이 노드가 리더면 1").register(registry);
        Gauge.builder("waitingroom.snapshot.age.seconds", holder, current -> current.ageMillis() / 1000.0)
                .description("판정 재료 발행 뒤 나이(Redis 시각 기준). 아직 없으면 음수").register(registry);
        Gauge.builder("waitingroom.gateways", holder,
                        current -> current.current().map(snapshot -> snapshot.meta().gatewayCount()).orElse(0))
                .description("판정 재료가 본 살아 있는 게이트웨이 수").register(registry);
        waiting = MultiGauge.builder("waitingroom.queue.waiting").description("모델별 대기 인원").register(registry);
        credit = MultiGauge.builder("waitingroom.queue.credit").description("모델별 초당 입장 몫").register(registry);
    }

    void observe(GatewaySnapshot snapshot) {
        waiting.register(snapshot.products().entrySet().stream()
                .map(entry -> MultiGauge.Row.of(Tags.of("product", entry.getKey()), entry.getValue().waiting()))
                .toList(), true);
        credit.register(snapshot.products().entrySet().stream()
                .map(entry -> MultiGauge.Row.of(Tags.of("product", entry.getKey()), entry.getValue().credit()))
                .toList(), true);
    }

    void admitted(String productKey, long entered) {
        if (entered > 0) {
            Counter.builder("waitingroom.admitted").tag("product", productKey).register(registry).increment(entered);
        }
    }

    void resyncRequested() {
        Counter.builder("waitingroom.schedule.resync.requests").register(registry).increment();
    }

    void loopFailed(String loop) {
        Counter.builder("waitingroom.control.failures").tag("loop", loop).register(registry).increment();
    }
}
