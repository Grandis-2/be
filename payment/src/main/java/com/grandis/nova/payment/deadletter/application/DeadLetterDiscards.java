package com.grandis.nova.payment.deadletter.application;

import com.grandis.nova.payment.deadletter.domain.DeadLetterEvent;
import com.grandis.nova.payment.deadletter.domain.DeadLetterWaitingQuery;
import com.grandis.nova.payment.event.InboundEventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * 버린 DLQ 메시지를 ERROR 로그와 카운터(payment.dlq.discarded, eventType 별)로 드러낸다. 버린 환불 요청은 다시 오지 않는다 —
 * order 에는 환불 요청을 다시 보내는 길이 없어 그 주문은 취소 중에 머문다. 사람이 결제 · 주문을 보고 직접 환불을 마쳐야 한다.
 *
 * eventType 은 받는 종류 · OTHER · UNKNOWN 으로 접는다(대기 지표와 같다). 낼 수 있는 값을 처음부터 0 으로 등록한다 —
 * 첫 증가 전에 시계열이 없으면 increase() 가 그 첫 번을 놓친다.
 */
@Component
class DeadLetterDiscards {

    static final String METER = "payment.dlq.discarded";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterDiscards.class);

    private final MeterRegistry registry;

    DeadLetterDiscards(MeterRegistry registry) {
        this.registry = registry;
        Stream.concat(Arrays.stream(InboundEventType.values()).map(Enum::name),
                Stream.of(DeadLetterWaitingQuery.OTHER, DeadLetterWaitingQuery.UNKNOWN)).forEach(this::counter);
    }

    /** 버리기가 커밋된 뒤 부른다. note 는 관리자가 쓴 까닭이다(결제 키를 싣지 않는다). */
    void discarded(DeadLetterEvent event, String discardedBy, String note) {
        if (InboundEventType.ORDER_REFUND_REQUESTED.name().equals(event.getEventType())) {
            log.error("환불 요청을 버렸다 — 주문은 취소 중 그대로, 수동 환불 확인 필요 deadLetterId={} eventType={} aggregateId={}"
                    + " discardedBy={} note={}", event.getId(), event.getEventType(), event.getAggregateId(), discardedBy, note);
        } else {
            log.error("DLQ 메시지를 버렸다 — 처리되지 않은 채 끝났다, 보낸 쪽 확인 필요 deadLetterId={} eventType={} aggregateId={}"
                    + " discardedBy={} note={}", event.getId(), event.getEventType(), event.getAggregateId(), discardedBy, note);
        }
        counter(kind(event.getEventType())).increment();
    }

    private static String kind(String eventType) {
        if (eventType == null) {
            return DeadLetterWaitingQuery.UNKNOWN;
        }
        return InboundEventType.isKnown(eventType) ? eventType : DeadLetterWaitingQuery.OTHER;
    }

    private Counter counter(String eventType) {
        return Counter.builder(METER).tag("eventType", eventType).register(registry);
    }
}
