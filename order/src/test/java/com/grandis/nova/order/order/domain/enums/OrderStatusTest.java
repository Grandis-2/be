package com.grandis.nova.order.order.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.grandis.nova.order.order.domain.enums.OrderStatus.AUTHORIZING;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.AWAITING_CONFIRMATION;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.AWAITING_PAYMENT;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELED;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELING;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.DELIVERED;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.PREPARING_ITEMS;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.READY_TO_SHIP;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.SHIPPED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.CANCEL_REQUESTED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.PACKED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.PAYMENT_APPROVED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.PAYMENT_DECLINED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.PAYMENT_REQUESTED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.PREPARATION_STARTED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.REFUND_COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태 · 사건의 모든 조합(9 × 9). 아래 ALLOWED 가 기대 전이표다 — 결제(요청 · 승인 · 거절) · 준비 · 포장 · 출고 · 배송 완료의
 * 정방향, 결제 대기 중 취소는 바로 CANCELED(승인 중에는 취소 없음), 결제 후 · 출고 전 취소는 CANCELING 을 거쳐 환불 완료로
 * CANCELED, 출고 뒤에는 취소 없음.
 * 여기 없는 조합은 전이하지 않는다(OrderStatus.next 가 비어 있다).
 */
class OrderStatusTest {

    static final Map<OrderStatus, Map<OrderTrigger, OrderStatus>> ALLOWED = Map.of(
            AWAITING_PAYMENT, Map.of(CANCEL_REQUESTED, CANCELED, PAYMENT_REQUESTED, AUTHORIZING),
            AUTHORIZING, Map.of(PAYMENT_APPROVED, AWAITING_CONFIRMATION, PAYMENT_DECLINED, AWAITING_PAYMENT),
            AWAITING_CONFIRMATION, Map.of(CANCEL_REQUESTED, CANCELING, PREPARATION_STARTED, PREPARING_ITEMS),
            PREPARING_ITEMS, Map.of(CANCEL_REQUESTED, CANCELING, PACKED, READY_TO_SHIP),
            READY_TO_SHIP, Map.of(CANCEL_REQUESTED, CANCELING, OrderTrigger.SHIPPED, SHIPPED),
            SHIPPED, Map.of(OrderTrigger.DELIVERED, DELIVERED),
            DELIVERED, Map.of(),
            CANCELING, Map.of(REFUND_COMPLETED, CANCELED),
            CANCELED, Map.of());

    static Stream<Arguments> allCombinations() {
        return Stream.of(OrderStatus.values()).flatMap(status ->
                Stream.of(OrderTrigger.values()).map(trigger -> Arguments.of(status, trigger)));
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("allCombinations")
    void onlyAllowedCombinationsHaveNextStatus(OrderStatus status, OrderTrigger trigger) {
        Optional<OrderStatus> expected = Optional.ofNullable(ALLOWED.get(status).get(trigger));

        assertThat(status.next(trigger)).isEqualTo(expected);
    }

    @Test
    void everyStatusIsInTheTable() {
        assertThat(ALLOWED.keySet()).containsExactlyInAnyOrder(OrderStatus.values());
    }

    // 예약 취소 수신의 거절과 취소 가능 조회가 같이 쓰는 규칙이다.
    @Test
    void onlyShippedOrDeliveredOrdersRejectCancel() {
        assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(s -> !s.isCancelable()))
                .containsExactlyInAnyOrder(SHIPPED, DELIVERED);
    }
}
