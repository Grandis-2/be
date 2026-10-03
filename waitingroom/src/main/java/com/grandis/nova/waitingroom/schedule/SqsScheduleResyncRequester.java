package com.grandis.nova.waitingroom.schedule;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.waitingroom.control.ScheduleResyncRequester;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * preorder 의 소비 큐로 CAMPAIGN_RESYNC_REQUESTED 를 보낸다. 봉투 · 메시지 속성(eventType · eventId)은 서비스들의
 * 아웃박스 전송과 같다. 대상이 전체라 aggregateId 는 비운다. SQS 클라이언트가 블로킹이라 이벤트 루프 밖에서 부른다.
 */
class SqsScheduleResyncRequester implements ScheduleResyncRequester {

    static final String EVENT_TYPE = "CAMPAIGN_RESYNC_REQUESTED";
    static final String AGGREGATE_TYPE = "PREORDER_CAMPAIGN";
    static final String REQUESTED_BY = "waitingroom";

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final String queue;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    SqsScheduleResyncRequester(SqsClient sqs, SqsQueueUrls queueUrls, String queue, JsonMapper jsonMapper, Clock clock) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.queue = queue;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public Mono<Void> request(String reason) {
        return Mono.fromRunnable(() -> {
            String eventId = UUID.randomUUID().toString();
            EventEnvelope envelope = new EventEnvelope(eventId, EVENT_TYPE, AGGREGATE_TYPE, null, clock.instant(),
                    jsonMapper.valueToTree(Map.of("requestedBy", REQUESTED_BY, "reason", reason)));
            sqs.sendMessage(request -> request
                    .queueUrl(queueUrls.of(queue))
                    .messageBody(jsonMapper.writeValueAsString(envelope))
                    .messageAttributes(Map.of("eventType", text(EVENT_TYPE), "eventId", text(eventId))));
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
