package com.grandis.nova.order.event;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelService;
import com.grandis.nova.order.order.pay.PaymentResults;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 받은 메시지를 이벤트 종류별 처리로 보낸다. 큐 소비기(common:sqs)는 본문을 그대로 여기에 넘긴다.
 *
 * 모르는 종류 · 깨진 본문은 예외로 올린다. 소비기는 메시지를 지우지 않고, 재수신 한도를 넘으면 DLQ 로 간다.
 */
@Component
public class OrderEventDispatcher {

    /** 예약 이벤트의 aggregate. aggregateId 가 예약 내부 id 라는 뜻이다. */
    static final String PREORDER_AGGREGATE = "PREORDER";
    /** 결제 결과의 aggregate. aggregateId 가 주문 id 라는 뜻이다. */
    static final String ORDER_AGGREGATE = "ORDER";

    private final SettlePreorderCancelService cancelSettlement;
    private final PaymentResults paymentResults;
    private final JsonMapper jsonMapper;

    public OrderEventDispatcher(SettlePreorderCancelService cancelSettlement, PaymentResults paymentResults,
                                JsonMapper jsonMapper) {
        this.cancelSettlement = cancelSettlement;
        this.paymentResults = paymentResults;
        this.jsonMapper = jsonMapper;
    }

    /** @throws IllegalArgumentException 받지 않는 이벤트 종류 · aggregate */
    public void dispatch(String body) {
        EventEnvelope envelope = jsonMapper.readValue(body, EventEnvelope.class);
        switch (InboundEventType.valueOf(envelope.eventType())) {
            case PREORDER_CANCEL_REQUESTED -> cancelSettlement.settle(
                    jsonMapper.treeToValue(envelope.payload(), PreorderCancelRequested.class)
                            .toCancel(aggregateId(envelope, PREORDER_AGGREGATE)));
            case ORDER_PAYMENT_SETTLED -> paymentResults.settle(
                    jsonMapper.treeToValue(envelope.payload(), OrderPaymentSettled.class)
                            .toSettlement(aggregateId(envelope, ORDER_AGGREGATE)));
        }
    }

    /** 대상은 봉투의 aggregateId 로 찾는다. 다른 aggregate 의 id 로 엉뚱한 주문을 바꾸지 않게 종류를 확인한다. */
    private static Long aggregateId(EventEnvelope envelope, String expectedType) {
        if (!expectedType.equals(envelope.aggregateType()) || envelope.aggregateId() == null) {
            throw new IllegalArgumentException("%s 이벤트가 아니다: eventId=%s, aggregateType=%s, aggregateId=%s"
                    .formatted(expectedType, envelope.eventId(), envelope.aggregateType(), envelope.aggregateId()));
        }
        return envelope.aggregateId();
    }
}
