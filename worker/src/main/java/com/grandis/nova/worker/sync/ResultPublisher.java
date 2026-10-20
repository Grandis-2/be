package com.grandis.nova.worker.sync;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.worker.event.Attempt;
import com.grandis.nova.worker.event.DrawResultReady;
import com.grandis.nova.worker.event.JobResult;
import com.grandis.nova.worker.event.ResultEventType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 결과 이벤트를 보낸다 — 예약 작업의 결과는 preorder-events, 추첨 결과는 order-events. worker 는 DB 가 없어 아웃박스 없이
 * 곧바로 보낸다 — 보내지 못하면 던지고, 부르는 쪽은 원본 메시지를 지우지 않아 같은 차례를 다시 한다. 이벤트 id 는 무엇의 결과인지로 정해,
 * 같은 차례를 다시 해 다시 보내도 같다 — 받는 쪽이 이미 받은 이벤트로 가린다.
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
     * @param result 종류에 맞는 결과(ExternalJobSucceeded · ExternalJobAttempted · ExternalJobFailed)
     * @throws RuntimeException 보내지 못했다
     */
    void publish(ResultEventType type, JobResult result) {
        Attempt attempt = result.attempt();
        // 실행 회차를 넣는다 — 재처리한 실행은 1세대 1차례부터 다시 세므로, 빼면 지난 실행의 이벤트로 가려진다
        String eventId = nameBased(type.name(), result.syncJobId(), attempt.run(), attempt.generation(), attempt.attempt());
        send(ResultEventType.QUEUE, eventId, type.name(), ResultEventType.AGGREGATE_TYPE, result.syncJobId(), result);
    }

    /**
     * 회차 하나에 추첨은 한 번이다. Mock 이 같은 결과를 재생하므로 다시 보내도 같은 이벤트다.
     *
     * @throws RuntimeException 보내지 못했다
     */
    void publishDrawResult(UUID drawId, DrawResultReady result) {
        send(DrawResultReady.QUEUE, nameBased(DrawResultReady.EVENT_TYPE, drawId), DrawResultReady.EVENT_TYPE,
                DrawResultReady.AGGREGATE_TYPE, drawId, result);
    }

    private static String nameBased(Object... parts) {
        StringBuilder name = new StringBuilder();
        for (Object part : parts) {
            name.append(part).append('/');
        }
        return UUID.nameUUIDFromBytes(name.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void send(String queue, String eventId, String eventType, String aggregateType, UUID aggregateId,
                      Object payload) {
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
