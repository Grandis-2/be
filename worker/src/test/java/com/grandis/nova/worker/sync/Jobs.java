package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.worker.mock.FakeMock;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 시험용 작업 하나. preorder 가 보내는 모양의 메시지를 만들고, worker 가 내보낸 결과 이벤트 · 다시 보낸 메시지를 꺼내 본다. */
final class Jobs {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final String REGISTER_QUEUE = "preorder-register";
    static final String EVENTS_QUEUE = "preorder-events";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final UUID syncJobId = UUID.randomUUID();
    /** 예약의 공개 id. 외부 mock 의 멱등 키다. */
    final String key = "PO-" + UUID.randomUUID();

    String body() {
        return body("\"request\":{\"ourReservationId\":\"%s\",\"qty\":1},".formatted(key));
    }

    /** @param request payload 의 request 칸(끝에 쉼표까지). 빈 문자열이면 보낼 내용이 없는 메시지다 */
    String body(String request) {
        return """
                {"eventId":"%s","eventType":"REGISTER_JOB_READY","aggregateType":"PREORDER_SYNC_JOB","aggregateId":"%s",
                 "occurredAt":"2026-10-12T01:00:00Z",
                 "payload":{"syncJobId":"%s","preorderId":"%s",%s"actor":"SYSTEM","run":1,"jobType":"REGISTER"}}
                """.formatted(UUID.randomUUID(), syncJobId, syncJobId, key, request);
    }

    /** 큐에서 받은 것처럼 만든 메시지. */
    Message message(Map<String, String> attributes) {
        return Message.builder().messageId(UUID.randomUUID().toString()).receiptHandle("handle").body(body())
                .messageAttributes(attributes(attributes)).build();
    }

    static Map<String, MessageAttributeValue> attributes(Map<String, String> values) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put("eventType", text("REGISTER_JOB_READY"));
        values.forEach((name, value) -> attributes.put(name, text(value)));
        return attributes;
    }

    /** worker 가 preorder 에 알린 결과 이벤트(봉투). 받은 것은 큐에서 지운다. */
    Optional<JsonNode> event(TestQueues queues, Duration timeout) {
        return queues.receive(EVENTS_QUEUE, message -> message.body().contains(syncJobId.toString()), timeout)
                .map(message -> JSON.readTree(message.body()));
    }

    JsonNode event(TestQueues queues) {
        return event(queues, TIMEOUT).orElseThrow(() -> new AssertionError("결과 이벤트가 오지 않았다"));
    }

    /** worker 가 지연을 두고 다시 보낸 작업 메시지의 속성(이름 → 값). 받은 것은 큐에서 지운다. */
    Optional<Map<String, String>> resent(TestQueues queues, Duration timeout) {
        return queues.receive(REGISTER_QUEUE, message -> message.body().contains(syncJobId.toString()), timeout)
                .map(message -> {
                    Map<String, String> values = new HashMap<>();
                    message.messageAttributes().forEach((name, value) -> values.put(name, value.stringValue()));
                    return values;
                });
    }

    Map<String, String> resent(TestQueues queues) {
        return resent(queues, TIMEOUT).orElseThrow(() -> new AssertionError("다시 보낸 메시지가 없다"));
    }

    /** 이 작업의 키로 외부 mock 이 받은 요청("METHOD 경로"). */
    List<String> calls(FakeMock mock) {
        return mock.requests().stream()
                .filter(request -> key.equals(request.idempotencyKey()) || request.path().endsWith(key))
                .map(request -> request.method() + " " + request.path().replace(key, "{key}"))
                .toList();
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
