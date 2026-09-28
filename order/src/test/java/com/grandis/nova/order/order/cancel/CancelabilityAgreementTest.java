package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.outbox.PreorderOrderSettled;
import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.outbox.PreorderOrderSettled.Result;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 취소 가능 조회(OrderStatus.isCancelable)와 예약 취소 수신의 배송 거절(settledFor 의 SHIPPED · DELIVERED 줄)은
 * 따로 적힌 규칙이다. 둘이 어긋나면 조회는 "가능" 이라 하고 실제 취소는 REJECTED(SHIPPED) 가 된다 — 모든 상태에서 맞물리는지 본다.
 */
class CancelabilityAgreementTest {

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void shipmentRejectionMatchesCancelability(OrderStatus status) {
        for (CancelReason reason : CancelReason.values()) {
            assertThat(rejectedForShipment(status, reason))
                    .as("status=%s reason=%s", status, reason)
                    .isEqualTo(!status.isCancelable());
        }
    }

    /** 결과 대기 · 불가능한 경우의 예외는 "배송 거절이 아님" 이다. */
    private static boolean rejectedForShipment(OrderStatus status, CancelReason reason) {
        PreorderOrderSettled settled;
        try {
            settled = PreorderCancelSettlement.settledFor(status,
                    new PreorderCancel(50231L, "9f1c-preorder", 1024L, reason, 3L));
        } catch (SettlementDeferredException | IllegalStateException e) {
            return false;
        }
        return settled.result() == Result.REJECTED && settled.reason() == RejectReason.SHIPPED;
    }
}
