package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.outbox.PreorderOrderSettled;
import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.outbox.PreorderOrderSettled.Result;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 원장이 돌려준 상태 × 취소 사유 → 정리 결과. 판정 표(계획서 §5)와 1:1 이다. 모든 상태 · 사유 조합을 돈다. */
class SettlementDecisionTest {

    static final EnumSet<OrderStatus> PAID =
            EnumSet.of(OrderStatus.AWAITING_CONFIRMATION, OrderStatus.PREPARING_ITEMS, OrderStatus.READY_TO_SHIP);

    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void 취소된_주문은_사유와_관계없이_CANCELED(CancelReason reason) {
        PreorderOrderSettled settled = SettlePreorderCancelService.settledFor(OrderStatus.CANCELED, cancel(reason));

        assertThat(settled.result()).isEqualTo(Result.CANCELED);
        assertThat(settled.reason()).isNull();
        assertThat(settled.cancelSequence()).isEqualTo(3L);
        assertThat(settled.preorderInternalId()).isEqualTo(50231L);
        assertThat(settled.preorderId()).isEqualTo("9f1c-preorder");
    }

    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void 출고된_주문은_사유와_관계없이_SHIPPED_로_거절(CancelReason reason) {
        for (OrderStatus status : EnumSet.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED)) {
            PreorderOrderSettled settled = SettlePreorderCancelService.settledFor(status, cancel(reason));

            assertThat(settled.result()).as("%s", status).isEqualTo(Result.REJECTED);
            assertThat(settled.reason()).as("%s", status).isEqualTo(RejectReason.SHIPPED);
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"AWAITING_CONFIRMATION", "PREPARING_ITEMS", "READY_TO_SHIP"})
    void 결제된_주문의_만료_취소는_PAID_로_거절(OrderStatus status) {
        PreorderOrderSettled settled = SettlePreorderCancelService.settledFor(status, cancel(CancelReason.EXPIRY));

        assertThat(settled.result()).isEqualTo(Result.REJECTED);
        assertThat(settled.reason()).isEqualTo(RejectReason.PAID);
    }

    /** 환불이 필요하다 — 결제 작업 몫. 결과를 적지 않고 미룬다. */
    @ParameterizedTest
    @EnumSource(value = CancelReason.class, names = "EXPIRY", mode = EnumSource.Mode.EXCLUDE)
    void 결제된_주문의_만료_외_취소는_결과를_미룬다(CancelReason reason) {
        for (OrderStatus status : PAID) {
            assertThatThrownBy(() -> SettlePreorderCancelService.settledFor(status, cancel(reason)))
                    .as("%s × %s", status, reason)
                    .isInstanceOf(SettlementDeferredException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void 승인_결과_대기와_환불_중은_사유와_관계없이_결과를_미룬다(CancelReason reason) {
        for (OrderStatus status : EnumSet.of(OrderStatus.AUTHORIZING, OrderStatus.CANCELING)) {
            assertThatThrownBy(() -> SettlePreorderCancelService.settledFor(status, cancel(reason)))
                    .as("%s × %s", status, reason)
                    .isInstanceOf(SettlementDeferredException.class);
        }
    }

    /** 미결제는 원장의 전제에 있어 취소됐어야 한다. 이 상태가 돌아오면 코드 버그다. */
    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void 미결제가_돌아오면_버그로_본다(CancelReason reason) {
        assertThatThrownBy(() -> SettlePreorderCancelService.settledFor(OrderStatus.AWAITING_PAYMENT, cancel(reason)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static SettlePreorderCancelCommand cancel(CancelReason reason) {
        return new SettlePreorderCancelCommand(50231L, "9f1c-preorder", 1024L, reason, 3L);
    }
}
