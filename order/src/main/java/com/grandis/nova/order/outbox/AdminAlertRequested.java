package com.grandis.nova.order.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 관리자 알림 한 건(contracts/admin-alert-events.md). 허브가 받아 묶음 · 해소 규칙대로 저장한다. 보내는 쪽은 사실만 싣는다 — 받는 사람 ·
 * 심각도 · 문구 · 상품이나 회차 이름은 허브와 프론트가 정한다.
 *
 * 종류마다 metrics 칸이 다르다. 종류와 칸이 어긋나지 않게 정적 팩토리로 만든다. 없는 값은 칸을 빼지 않고 null 로 싣는다(계약).
 *
 * @param aggregateId 알림이 생긴 원인의 id(종류는 type 이 정한다). 봉투로만 나가고 payload 에는 싣지 않는다
 */
public record AdminAlertRequested(@JsonIgnore UUID aggregateId, Type type, Target target, Metrics metrics) implements OutboxMessage {

    public AdminAlertRequested {
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        if (!type.metrics.isInstance(metrics)) {
            throw new IllegalArgumentException("종류와 metrics 가 다르다: type=" + type + ", metrics=" + metrics);
        }
    }

    /** 드로우 결과를 반영했다 — 반영 표식을 적은 트랜잭션에서 한 번. */
    public static AdminAlertRequested drawSettled(UUID drawId, long winners, int winnerCount, Instant closesAt, Instant settledAt) {
        return new AdminAlertRequested(drawId, Type.DRAW_SETTLED, Target.draw(drawId),
                new DrawSettledMetrics(winners, winnerCount, closesAt, settledAt));
    }

    /** 마감 뒤 오래 반영하지 못한 회차 — 찾을 때마다(같은 회차에 여러 번 와도 허브가 묶는다). */
    public static AdminAlertRequested drawDelayed(UUID drawId, Instant closesAt, Instant drawRequestedAt, long authorizingEntries,
                                                  long paidEntries) {
        return new AdminAlertRequested(drawId, Type.DRAW_DELAYED, Target.draw(drawId),
                new DrawDelayedMetrics(closesAt, drawRequestedAt, authorizingEntries, paidEntries));
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.ADMIN_ALERT_REQUESTED;
    }

    @Override
    public AggregateType aggregateType() {
        return type.aggregateType;
    }

    /** order 가 보내는 알림 종류 · 원인 · metrics 모양. 계약의 표와 같아야 한다. */
    public enum Type {
        DRAW_SETTLED(AggregateType.DRAW_CAMPAIGN, DrawSettledMetrics.class),
        DRAW_DELAYED(AggregateType.DRAW_CAMPAIGN, DrawDelayedMetrics.class);

        private final AggregateType aggregateType;
        private final Class<? extends Metrics> metrics;

        Type(AggregateType aggregateType, Class<? extends Metrics> metrics) {
            this.aggregateType = aggregateType;
            this.metrics = metrics;
        }
    }

    public enum TargetKind {
        /** 드로우 회차 id */
        DRAW
    }

    public record Target(TargetKind kind, String id) {

        public Target {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }

        static Target draw(UUID drawId) {
            return new Target(TargetKind.DRAW, drawId.toString());
        }
    }

    public sealed interface Metrics permits DrawSettledMetrics, DrawDelayedMetrics {
    }

    /**
     * @param winners     실제 당첨 수(0원 주문을 만든 응모). 결제 완료 응모가 없던 회차는 0
     * @param winnerCount 설정한 당첨 인원
     */
    public record DrawSettledMetrics(long winners, int winnerCount, Instant closesAt, Instant settledAt) implements Metrics {
    }

    /** @param drawRequestedAt Mock 에 추첨을 요청한 시각. 아직 요청하지 않았으면(결제 확인 중이 남음) null */
    public record DrawDelayedMetrics(Instant closesAt, Instant drawRequestedAt, long authorizingEntries, long paidEntries) implements Metrics {
    }
}
