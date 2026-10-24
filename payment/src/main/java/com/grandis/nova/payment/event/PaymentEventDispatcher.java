package com.grandis.nova.payment.event;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.payment.confirm.ConfirmHandover;
import com.grandis.nova.payment.refund.RefundRequests;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderOrderId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 받은 메시지를 이벤트 종류별 처리로 보낸다. 큐 소비기(common:sqs)는 본문을 그대로 여기에 넘긴다.
 *
 * 모르는 종류 · 깨진 본문은 예외로 올린다. 소비기는 메시지를 지우지 않고, 재수신 한도를 넘으면 DLQ 로 간다.
 * 처리가 "아직 이르다" 고 하면 미룰 시간을 돌려준다 — 소비기가 같은 큐에 그만큼 지연을 두고 다시 보낸 뒤 원본을 지운다.
 */
@Component
public class PaymentEventDispatcher {

    private final RefundRequests refundRequests;
    private final ConfirmHandover confirmHandover;
    private final JsonMapper jsonMapper;

    public PaymentEventDispatcher(RefundRequests refundRequests, ConfirmHandover confirmHandover,
                                  JsonMapper jsonMapper) {
        this.refundRequests = refundRequests;
        this.confirmHandover = confirmHandover;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @return 다시 볼 때까지 미룰 시간. 비어 있으면 처리를 마쳤다
     * @throws IllegalArgumentException 받지 않는 이벤트 종류 · aggregate
     */
    public Optional<Duration> dispatch(String body) {
        EventEnvelope envelope = jsonMapper.readValue(body, EventEnvelope.class);
        return switch (InboundEventType.valueOf(envelope.eventType())) {
            case ORDER_REFUND_REQUESTED -> {
                refundRequests.request(PaymentTarget.order(orderId(envelope)),
                        jsonMapper.treeToValue(envelope.payload(), OrderRefundRequested.class).amount());
                yield Optional.empty();
            }
            case ORDER_PAYMENT_AUTHORIZING -> {
                OrderPaymentAuthorizing payload = jsonMapper.treeToValue(envelope.payload(),
                        OrderPaymentAuthorizing.class);
                yield confirmHandover.handle(PaymentTarget.order(orderId(envelope)),
                        new ProviderOrderId(payload.providerOrderId()), new Money(payload.amount()));
            }
        };
    }

    /** 대상은 봉투의 aggregateId 로 찾는다. 다른 aggregate 의 id 로 엉뚱한 결제를 환불하지 않게 종류를 확인한다. */
    private static UUID orderId(EventEnvelope envelope) {
        if (!InboundEventType.ORDER_AGGREGATE.equals(envelope.aggregateType()) || envelope.aggregateId() == null) {
            throw new IllegalArgumentException("%s 이벤트가 아니다: eventId=%s, aggregateType=%s, aggregateId=%s"
                    .formatted(InboundEventType.ORDER_AGGREGATE, envelope.eventId(), envelope.aggregateType(), envelope.aggregateId()));
        }
        return envelope.aggregateId();
    }
}
