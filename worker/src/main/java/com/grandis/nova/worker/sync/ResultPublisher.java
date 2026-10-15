package com.grandis.nova.worker.sync;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.worker.event.DrawResultReady;
import com.grandis.nova.worker.event.ResultEventType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 결과 이벤트를 보낸다 — 예약 작업의 결과는 preorder-events, 추첨 결과는 order-events. worker 는 DB 가 없어 아웃박스 없이
 * 곧바로 보낸다 — 보내지 못하면 던지고, 부르는 쪽은 원본 메시지를 지우지 않아 같은 차례를 다시 한다. 이벤트 id 는 보낼 때마다 새로 만든다.
 */
class ResultPublisher {

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final JsonMapper json;
    private final Clock clock;

    ResultPublisher(SqsClient sqs, SqsQueueUrls queueUrls, JsonMapper json, Clock clock) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param payload 종류에 맞는 결과(ExternalJobSucceeded · ExternalJobAttempted · ExternalJobFailed)
     * @throws RuntimeException 보내지 못했다
     */
    void publish(ResultEventType type, UUID syncJobId, Object payload) {
        send(ResultEventType.QUEUE, type.name(), ResultEventType.AGGREGATE_TYPE, syncJobId, payload);
    }

    /** @throws RuntimeException 보내지 못했다 */
    void publishDrawResult(UUID drawId, DrawResultReady result) {
        send(DrawResultReady.QUEUE, DrawResultReady.EVENT_TYPE, DrawResultReady.AGGREGATE_TYPE, drawId, result);
    }

    private void send(String queue, String eventType, String aggregateType, UUID aggregateId, Object payload) {
        String eventId = UUID.randomUUID().toString();
        String body = json.writeValueAsString(new EventEnvelope(eventId, eventType, aggregateType, aggregateId,
                clock.instant(), json.valueToTree(payload)));
        sqs.sendMessage(request -> request.queueUrl(queueUrls.of(queue))
                .messageBody(body)
                .messageAttributes(Map.of("eventType", text(eventType), "eventId", text(eventId))));
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
