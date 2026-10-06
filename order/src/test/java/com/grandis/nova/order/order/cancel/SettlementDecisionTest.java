package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.outbox.PreorderOrderSettled;
import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.outbox.PreorderOrderSettled.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원장이 돌려준 상태 × 취소 사유 → 정리 결과, 그리고 사유별 원장 전제. 모든 상태 · 사유 조합을 돈다. 판정은 아래 테스트 이름 그대로다:
 * CANCELED → CANCELED, 출고 뒤(SHIPPED · DELIVERED) → REJECTED(SHIPPED), 결제됨 + 만료 → REJECTED(PAID),
 * 승인 결과 대기 · 환불 중(환불 실패 포함) → 보류, 미결제 · 결제됨(만료 외)이 돌아오면 버그(원장이 바꿨어야 한다).
 */
class SettlementDecisionTest {

    static final EnumSet<OrderStatus> PAID =
            EnumSet.of(OrderStatus.AWAITING_CONFIRMATION, OrderStatus.PREPARING_ITEMS, OrderStatus.READY_TO_SHIP);

    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void 취소된_주문은_사유와_관계없이_CANCELED(CancelReason reason) {
        PreorderOrderSettled settled = settled(OrderStatus.CANCELED, reason);

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
            PreorderOrderSettled settled = settled(status, reason);

            assertThat(settled.result()).as("%s", status).isEqualTo(Result.REJECTED);
            assertThat(settled.reason()).as("%s", status).isEqualTo(RejectReason.SHIPPED);
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"AWAITING_CONFIRMATION", "PREPARING_ITEMS", "READY_TO_SHIP"})
    void 결제된_주문의_만료_취소는_PAID_로_거절(OrderStatus status) {
        PreorderOrderSettled settled = settled(status, CancelReason.EXPIRY);

        assertThat(settled.result()).isEqualTo(Result.REJECTED);
        assertThat(settled.reason()).isEqualTo(RejectReason.PAID);
    }

    /** 만료 외 취소의 전제에 결제됨이 있어 원장이 취소 중으로 바꿨어야 한다. 결제됨이 돌아오면 코드 버그다. */
    @ParameterizedTest
    @EnumSource(value = CancelReason.class, names = "EXPIRY", mode = EnumSource.Mode.EXCLUDE)
    void 결제된_주문이_만료_외_취소_뒤에도_결제됨이면_버그로_본다(CancelReason reason) {
        for (OrderStatus status : PAID) {
            assertThatThrownBy(() -> SettlePreorderCancelService.settledFor(status, cancel(reason)))
                    .as("%s × %s", status, reason)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void 승인_결과_대기와_환불_중은_사유와_관계없이_보류한다(CancelReason reason) {
        for (OrderStatus status : EnumSet.of(OrderStatus.AUTHORIZING, OrderStatus.CANCELING)) {
            assertThat(SettlePreorderCancelService.settledFor(status, cancel(reason)))
                    .as("%s × %s", status, reason)
                    .isEqualTo(new CancelSettlement.Deferred(status));
        }
    }

    /** 미결제는 원장의 전제에 있어 취소됐어야 한다. 이 상태가 돌아오면 코드 버그다. */
    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void 미결제가_돌아오면_버그로_본다(CancelReason reason) {
        assertThatThrownBy(() -> SettlePreorderCancelService.settledFor(OrderStatus.AWAITING_PAYMENT, cancel(reason))).isInstanceOf(IllegalStateException.class);
    }

    // 만료는 미결제만 취소한다 — 그사이 결제됐으면 환불하지 않고 거절한다(만료 경합)
    @Test
    void 만료_취소의_전제는_미결제뿐이다() {
        assertThat(SettlePreorderCancelService.cancelableFrom(CancelReason.EXPIRY))
                .containsExactly(OrderStatus.AWAITING_PAYMENT);
    }

    @ParameterizedTest
    @EnumSource(value = CancelReason.class, names = "EXPIRY", mode = EnumSource.Mode.EXCLUDE)
    void 만료_외_취소의_전제는_출고_전_미결제와_결제됨이다(CancelReason reason) {
        assertThat(SettlePreorderCancelService.cancelableFrom(reason))
                .containsExactlyInAnyOrder(OrderStatus.AWAITING_PAYMENT, OrderStatus.AWAITING_CONFIRMATION,
                        OrderStatus.PREPARING_ITEMS, OrderStatus.READY_TO_SHIP);
    }

    private static PreorderOrderSettled settled(OrderStatus status, CancelReason reason) {
        CancelSettlement settlement = SettlePreorderCancelService.settledFor(status, cancel(reason));
        assertThat(settlement).isInstanceOf(CancelSettlement.Settled.class);
        return ((CancelSettlement.Settled) settlement).result();
    }

    private static SettlePreorderCancelCommand cancel(CancelReason reason) {
        return new SettlePreorderCancelCommand(50231L, "9f1c-preorder", 1024L, reason, 3L);
    }
}
