package com.grandis.nova.waitingroom.control;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 제어 평면 지표 — 리더 여부, 판정 재료 나이, 게이트웨이 수, 모델별 대기 · 몫, 입장 누적, 루프 실패.
 * 모델별 값은 판정 재료를 받을 때마다 통째로 갈아 끼우고, 입장 누적은 일정에서 빠진 모델 것을 지운다(끝난 모델이 남지 않게).
 */
@Component
public class ControlMetrics {

    static final String ADMITTED = "waitingroom.admitted";
    private static final String PRODUCT = "product";

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
        // 운영값은 리더가 판정 재료에 실어 보낸 값(적용된 값)이다
        Gauge.builder("waitingroom.settings.global.credit", holder,
                current -> current.current().map(snapshot -> (double) snapshot.meta().globalCredit()).orElse(Double.NaN))
                .description("적용된 전역 초당 입장 인원. 0 은 입장 일시 정지, 재료가 없으면 NaN").register(registry);
        Gauge.builder("waitingroom.settings.max.wait.seconds", holder, current -> current.current()
                        .map(snapshot -> snapshot.meta().maxWait().duration() == null ? -1.0
                                : (double) snapshot.meta().maxWait().duration().toSeconds())
                        .orElse(Double.NaN))
                .description("적용된 최대 대기 시간. -1 은 제한 없음, 재료가 없으면 NaN").register(registry);
        Gauge.builder("waitingroom.brake.factor", holder,
                        current -> current.current().map(GatewaySnapshot::brakeFactor).orElse(Double.NaN))
                .description("운영값에 곱한 브레이크 배율. 1 은 걸리지 않음, 재료가 없으면 NaN").register(registry);
        Gauge.builder("waitingroom.settings.operational.credit", holder, current -> current.current()
                        .map(GatewaySnapshot::capacity).filter(CapacityStatus::operationalKnown)
                        .map(capacity -> (double) capacity.operationalCredit()).orElse(Double.NaN))
                .description("관리자 운영값(preorder 용량 상한 · 브레이크 전). 재료가 없거나 옛 재료면 NaN").register(registry);
        Gauge.builder("waitingroom.capacity.sum", holder, current -> knownCapacity(current)
                        .map(capacity -> (double) capacity.sum()).orElse(Double.NaN))
                .description("preorder 태스크 용량 합(갓 뜬 태스크는 줄여 셈). 표시가 없거나 읽지 못하면 NaN").register(registry);
        Gauge.builder("waitingroom.capacity.tasks", holder, current -> knownCapacity(current)
                        .map(capacity -> (double) capacity.ready()).orElse(Double.NaN))
                .tag("state", "ready").description("용량 합에 든 preorder 태스크 수. 용량을 모르면 NaN").register(registry);
        Gauge.builder("waitingroom.capacity.tasks", holder, current -> knownCapacity(current)
                        .map(capacity -> (double) capacity.warming()).orElse(Double.NaN))
                .tag("state", "warming").description("용량 합에 든 preorder 태스크 수. 용량을 모르면 NaN").register(registry);
        waiting = MultiGauge.builder("waitingroom.queue.waiting").description("모델별 대기 인원").register(registry);
        credit = MultiGauge.builder("waitingroom.queue.credit").description("모델별 초당 입장 몫").register(registry);
    }

    private static Optional<CapacityStatus> knownCapacity(SnapshotHolder holder) {
        return holder.current().map(GatewaySnapshot::capacity).filter(CapacityStatus::known);
    }

    void observe(GatewaySnapshot snapshot) {
        waiting.register(snapshot.products().entrySet().stream()
                .map(entry -> MultiGauge.Row.of(Tags.of("product", entry.getKey()), entry.getValue().waiting()))
                .toList(), true);
        credit.register(snapshot.products().entrySet().stream()
                .map(entry -> MultiGauge.Row.of(Tags.of("product", entry.getKey()), entry.getValue().credit()))
                .toList(), true);
    }

    /**
     * 입장 누적은 카운터라 갈아 끼울 수 없어, 이 모델들 밖의 것을 지운다 — 다시 들어오면 0 부터 센다.
     * 세는 쪽(배분 회차)과 같은 루프에서 불러야 방금 센 값을 지우지 않는다.
     */
    void retainAdmitted(Set<String> productKeys) {
        registry.find(ADMITTED).counters().stream()
                .filter(counter -> !productKeys.contains(counter.getId().getTag(PRODUCT)))
                .forEach(registry::remove);
    }

    void admitted(String productKey, long entered) {
        if (entered > 0) {
            Counter.builder(ADMITTED).tag(PRODUCT, productKey).register(registry).increment(entered);
        }
    }

    /** 끝난 모델 정리(retire · drop)의 결과. applied=false 는 읽은 뒤 값이 바뀌어 건너뛴 것이다. */
    void scheduleCleanup(String work, boolean applied) {
        Counter.builder("waitingroom.schedule.cleanup").tag("work", work).tag("outcome", applied ? "APPLIED" : "CONFLICT")
                .register(registry).increment();
    }

    /** 브레이크가 배율을 바꿨다. CUT · RECOVER. */
    void brakeChanged(String direction) {
        Counter.builder("waitingroom.brake.changes").tag("direction", direction).register(registry).increment();
    }

    /** 재발행을 요청했다. SCHEDULE_EMPTY · PERIODIC. */
    void resyncRequested(String reason) {
        Counter.builder("waitingroom.schedule.resync.requests").tag("reason", reason).register(registry).increment();
    }

    void loopFailed(String loop) {
        Counter.builder("waitingroom.control.failures").tag("loop", loop).register(registry).increment();
    }
}
