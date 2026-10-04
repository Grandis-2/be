package com.grandis.nova.order.event;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.event.OrderEventDispatcher.Handling;
import com.grandis.nova.order.order.cancel.CancelReason;
import com.grandis.nova.order.order.cancel.CancelSettlement;
import com.grandis.nova.order.order.cancel.RefundResults;
import com.grandis.nova.order.order.cancel.RefundSettlement;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelCommand;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelService;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.pay.PaymentSettlement;
import com.grandis.nova.order.outbox.PreorderOrderSettled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 큐 메시지 본문(공통 봉투)을 종류별 처리로 보내는지. 본문은 preorder · payment 가 보내는 모양 그대로다. */
class OrderEventDispatcherTest {

    static final long PREORDER_INTERNAL_ID = 50231L;
    static final String PREORDER_UUID = "9f1c2d3e-0000-4000-8000-000000000001";
    static final long ORDER_ID = 7702L;
    static final String PROVIDER_ORDER_ID = "5a1b2c3d-0000-4000-8000-000000000003";

    final JsonMapper jsonMapper = JsonMapper.builder().build();
    SettlePreorderCancelService settlement;
    PaymentResults paymentResults;
    RefundResults refundResults;
    OrderEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        settlement = mock(SettlePreorderCancelService.class);
        paymentResults = mock(PaymentResults.class);
        refundResults = mock(RefundResults.class);
        dispatcher = new OrderEventDispatcher(settlement, paymentResults, refundResults, jsonMapper);
    }

    @Test
    void 취소_요청은_봉투의_aggregateId_로_주문_정리에_넘긴다() {
        SettlePreorderCancelCommand cancel =
                new SettlePreorderCancelCommand(PREORDER_INTERNAL_ID, PREORDER_UUID, 1024L, CancelReason.USER, 3L);
        given(settlement.settle(cancel)).willReturn(new CancelSettlement.Settled(
                PreorderOrderSettled.canceled(PREORDER_INTERNAL_ID, PREORDER_UUID, 3L)));

        Handling handling = dispatcher.dispatch(
                envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", PREORDER_INTERNAL_ID, cancelRequested()));

        verify(settlement).settle(cancel);
        assertThat(handling).isEqualTo(Handling.DONE);
    }

    /** 지금 정할 수 없는 취소는 소비기가 늦춰 다시 받도록 알린다(지연 재발행). */
    @Test
    void 결과를_정할_수_없는_취소는_늦춰_다시_받도록_알린다() {
        given(settlement.settle(any())).willReturn(new CancelSettlement.Deferred(OrderStatus.CANCELING));

        Handling handling = dispatcher.dispatch(
                envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", PREORDER_INTERNAL_ID, cancelRequested()));

        assertThat(handling).isEqualTo(Handling.DEFER);
    }

    /** 환불 결과는 봉투의 aggregateId(주문 id)로 반영한다. 본문은 payment outbox.OrderRefundSettled 의 칸 이름 그대로다. */
    @Test
    void 환불_결과는_봉투의_aggregateId_로_환불_반영에_넘긴다() {
        dispatcher.dispatch(envelope("ORDER_REFUND_SETTLED", "ORDER", ORDER_ID, refunded()));
        dispatcher.dispatch(envelope("ORDER_REFUND_SETTLED", "ORDER", ORDER_ID, refundFailed()));

        verify(refundResults).settle(new RefundSettlement(ORDER_ID, RefundSettlement.Result.REFUNDED,
                new BigDecimal("15000")));
        verify(refundResults).settle(new RefundSettlement(ORDER_ID, RefundSettlement.Result.FAILED,
                new BigDecimal("15000")));
    }

    @Test
    void 주문이_아닌_aggregate_의_환불_결과나_결과와_맞지_않는_칸은_예외로_올린다() {
        String otherAggregate = envelope("ORDER_REFUND_SETTLED", "PREORDER", ORDER_ID, refunded());
        String failedWithTime = envelope("ORDER_REFUND_SETTLED", "ORDER", ORDER_ID,
                refundFailed().put("refundedAt", "2026-10-04T01:20:30Z"));
        String refundedWithoutTime = envelope("ORDER_REFUND_SETTLED", "ORDER", ORDER_ID,
                refunded().putNull("refundedAt"));

        assertThatThrownBy(() -> dispatcher.dispatch(otherAggregate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(failedWithTime)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(refundedWithoutTime)).isInstanceOf(JacksonException.class);
        verifyNoInteractions(refundResults);
    }

    @Test
    void 받지_않는_이벤트_종류는_조용히_버리지_않고_예외로_올린다() {
        String body = envelope("SOMETHING_ELSE", "PREORDER", PREORDER_INTERNAL_ID, cancelRequested());

        assertThatThrownBy(() -> dispatcher.dispatch(body)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(settlement);
    }

    /** 다른 aggregate 의 id 를 예약 내부 id 로 믿으면 엉뚱한 주문을 정리한다. */
    @Test
    void 예약이_아닌_aggregate_이거나_aggregateId_가_없으면_예외로_올린다() {
        String otherAggregate = envelope("PREORDER_CANCEL_REQUESTED", "ORDER", PREORDER_INTERNAL_ID, cancelRequested());
        String withoutId = envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", null, cancelRequested());

        assertThatThrownBy(() -> dispatcher.dispatch(otherAggregate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(withoutId)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(settlement);
    }

    @Test
    void 깨진_본문이나_모르는_사유나_시도_순번_없는_요청은_예외로_올린다() {
        String unknownReason = envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", PREORDER_INTERNAL_ID,
                cancelRequested().put("reason", "MAYBE"));
        ObjectNode noSequence = cancelRequested();
        noSequence.remove("cancelSequence");
        String withoutSequence = envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", PREORDER_INTERNAL_ID, noSequence);

        assertThatThrownBy(() -> dispatcher.dispatch("not-json")).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(unknownReason)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(withoutSequence)).isInstanceOf(JacksonException.class);
        verifyNoInteractions(settlement);
    }

    /** 결제 결과는 봉투의 aggregateId(주문 id)로 반영한다. 본문은 payment 가 보내는 모양 그대로다. */
    @Test
    void 결제_결과는_봉투의_aggregateId_로_결과_반영에_넘긴다() {
        dispatcher.dispatch(envelope("ORDER_PAYMENT_SETTLED", "ORDER", ORDER_ID, approved()));
        dispatcher.dispatch(envelope("ORDER_PAYMENT_SETTLED", "ORDER", ORDER_ID, declined()));

        verify(paymentResults).settle(new PaymentSettlement(ORDER_ID, PROVIDER_ORDER_ID,
                PaymentSettlement.Result.APPROVED, new BigDecimal("15000"), null));
        verify(paymentResults).settle(new PaymentSettlement(ORDER_ID, PROVIDER_ORDER_ID,
                PaymentSettlement.Result.DECLINED, new BigDecimal("15000"), DeclineReason.CARD_REJECTED));
    }

    @Test
    void 주문이_아닌_aggregate_의_결제_결과나_결과와_맞지_않는_칸은_예외로_올린다() {
        String otherAggregate = envelope("ORDER_PAYMENT_SETTLED", "PREORDER", ORDER_ID, approved());
        String approvedWithReason = envelope("ORDER_PAYMENT_SETTLED", "ORDER", ORDER_ID,
                approved().put("declineReason", "FAILED"));
        ObjectNode noAttempt = approved();
        noAttempt.remove("providerOrderId");

        assertThatThrownBy(() -> dispatcher.dispatch(otherAggregate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(approvedWithReason)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(envelope("ORDER_PAYMENT_SETTLED", "ORDER", ORDER_ID, noAttempt)))
                .isInstanceOf(JacksonException.class);
        verifyNoInteractions(paymentResults);
    }

    /**
     * 공통 봉투. 키는 계약 이름을 그대로 적는다 — 받는 쪽 record 를 직렬화해 만들면 이름이 어긋나도
     * 양쪽이 같이 바뀌어 잡지 못한다.
     */
    private String envelope(String eventType, String aggregateType, Long aggregateId, ObjectNode payload) {
        ObjectNode envelope = jsonMapper.createObjectNode()
                .put("eventId", "0b6f3c9e-0000-4000-8000-000000000002")
                .put("eventType", eventType)
                .put("aggregateType", aggregateType)
                .put("aggregateId", aggregateId)
                .put("occurredAt", "2026-09-03T01:00:03.470Z");
        envelope.set("payload", payload);
        return jsonMapper.writeValueAsString(envelope);
    }

    /** payment outbox.OrderPaymentSettled 의 칸 이름 그대로(결제 키 없음). */
    private ObjectNode approved() {
        return jsonMapper.createObjectNode()
                .put("providerOrderId", PROVIDER_ORDER_ID)
                .put("result", "APPROVED")
                .put("amount", 15000)
                .put("approvedAt", "2026-10-02T03:04:05Z")
                .putNull("declineReason");
    }

    private ObjectNode declined() {
        return jsonMapper.createObjectNode()
                .put("providerOrderId", PROVIDER_ORDER_ID)
                .put("result", "DECLINED")
                .put("amount", 15000)
                .putNull("approvedAt")
                .put("declineReason", "CARD_REJECTED");
    }

    /** payment outbox.OrderRefundSettled 의 칸 이름 그대로(결제 키 없음). */
    private ObjectNode refunded() {
        return jsonMapper.createObjectNode()
                .put("result", "REFUNDED")
                .put("amount", 15000)
                .put("refundedAt", "2026-10-04T01:20:30Z");
    }

    private ObjectNode refundFailed() {
        return jsonMapper.createObjectNode()
                .put("result", "FAILED")
                .put("amount", 15000)
                .putNull("refundedAt");
    }

    private ObjectNode cancelRequested() {
        return jsonMapper.createObjectNode()
                .put("preorderId", PREORDER_UUID)
                .put("customerId", 1024L)
                .put("reason", "USER")
                .put("cancelSequence", 3L);
    }
}
