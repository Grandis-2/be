package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.outbox.PreorderOrderSettled.Result;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 취소 가능 조회(OrderStatus.isCancelable)와 예약 취소 수신의 판정(원장 전제 cancelableFrom · 결과 settledFor)은 따로 적힌 규칙이다.
 * 둘이 어긋나면 조회는 "가능" 이라 하고 실제 취소는 REJECTED(SHIPPED) 가 되거나, 조회는 "불가" 라는데 취소가 일어난다 — 모든 상태 ·
 * 사유에서 맞물리는지 본다.
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

    /** 원장이 취소(취소 중)로 바꾸는 상태는 모두 조회에서도 취소 가능이다. */
    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void cancelableFromIsCancelable(CancelReason reason) {
        assertThat(SettlePreorderCancelService.cancelableFrom(reason))
                .allSatisfy(status -> assertThat(status.isCancelable()).as("%s × %s", status, reason).isTrue());
    }

    /** 보류 · 불가능한 경우는 "배송 거절이 아님" 이다. */
    private static boolean rejectedForShipment(OrderStatus status, CancelReason reason) {
        CancelSettlement settlement;
        try {
            settlement = SettlePreorderCancelService.settledFor(status,
                    new SettlePreorderCancelCommand(50231L, "9f1c-preorder", 1024L, reason, 3L));
        } catch (IllegalStateException e) {
            return false;
        }
        return settlement instanceof CancelSettlement.Settled settled
                && settled.result().result() == Result.REJECTED && settled.result().reason() == RejectReason.SHIPPED;
    }
}
