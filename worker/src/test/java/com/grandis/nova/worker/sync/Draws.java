package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.worker.mock.FakeMock;
import com.sun.net.httpserver.HttpExchange;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * 시험용 드로우 회차 하나. order 가 draw-register 로 보내는 모양의 메시지(응모 · 추첨 요청)를 만들고, worker 가 내보낸 추첨 결과 ·
 * DLQ · 돌려보낸 메시지를 꺼내 본다.
 */
final class Draws {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final String QUEUE = "draw-register";
    static final String DLQ = "draw-register-dlq";
    static final String RESULT_QUEUE = "order-events";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final UUID drawId = UUID.randomUUID();
    final UUID entryId = UUID.randomUUID();
    final UUID customerId = UUID.randomUUID();

    String entryBody() {
        return """
                {"eventId":"%s","eventType":"DRAW_ENTRY_PAID","aggregateType":"DRAW_ENTRY","aggregateId":"%s",
                 "occurredAt":"2026-10-14T03:00:00Z","payload":{"drawId":"%s","customerId":"%s"}}
                """.formatted(UUID.randomUUID(), entryId, drawId, customerId);
    }

    String requestedBody(int paidEntries, int winnerCount) {
        return """
                {"eventId":"%s","eventType":"DRAW_REQUESTED","aggregateType":"DRAW_CAMPAIGN","aggregateId":"%s",
                 "occurredAt":"2026-10-14T03:00:30Z","payload":{"paidEntries":%d,"winnerCount":%d}}
                """.formatted(UUID.randomUUID(), drawId, paidEntries, winnerCount);
    }

    /** 큐에서 받은 것처럼 만든 메시지. */
    Message message(String body, int receiveCount) {
        String eventType = JSON.readTree(body).get("eventType").asString();
        return Message.builder().messageId(UUID.randomUUID().toString()).receiptHandle("handle").body(body)
                .messageAttributes(Map.of("eventType", text(eventType), "eventId", text("evt-" + drawId)))
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, Integer.toString(receiveCount)))
                .build();
    }

    Message entry() {
        return message(entryBody(), 1);
    }

    Message requested(int paidEntries, int winnerCount) {
        return message(requestedBody(paidEntries, winnerCount), 1);
    }

    /** Mock 이 그 회차로 받은 응모 수(회차 조회 200). */
    static FakeMock.Handler status(int entryCount) {
        return FakeMock.json(200, """
                {"drawId":"d","status":"OPEN","entryCount":%d,"winnerCount":null,"winnerEntryIds":[],"drawnAt":null}"""
                .formatted(entryCount));
    }

    /** 추첨 200. */
    static FakeMock.Handler drawn(List<String> winners) {
        return FakeMock.json(200, """
                {"drawId":"d","status":"DRAWN","entryCount":%d,"winnerCount":%d,"winnerEntryIds":%s,"drawnAt":"2026-10-14T03:01:00Z"}"""
                .formatted(winners.size() + 1, winners.size(), JSON.writeValueAsString(winners)));
    }

    static List<String> winners(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID().toString()).toList();
    }

    static FakeMock.Handler error(int status, String errorCode) {
        return FakeMock.json(status, """
                {"errorCode":"%s","errorMessage":"message","replayable":false,"externalNumber":null}""".formatted(errorCode));
    }

    /** 회차 조회(GET)와 추첨(POST)에 따로 답한다. */
    static FakeMock.Handler routes(FakeMock.Handler status, FakeMock.Handler draw) {
        return (HttpExchange exchange) -> ("GET".equals(exchange.getRequestMethod()) ? status : draw).handle(exchange);
    }

    /** 이 회차 · 응모로 Mock 이 받은 요청("METHOD 경로"). */
    List<String> calls(FakeMock mock) {
        return mock.requests().stream()
                .filter(request -> request.path().contains(drawId.toString())
                        || entryId.toString().equals(request.idempotencyKey()))
                .map(request -> request.method() + " " + request.path().replace(drawId.toString(), "{drawId}"))
                .toList();
    }

    /** worker 가 order 에 보낸 추첨 결과(봉투)와 메시지 속성. 받은 것은 큐에서 지운다. */
    Optional<Message> result(TestQueues queues, Duration timeout) {
        return queues.receive(RESULT_QUEUE, message -> message.body().contains(drawId.toString()), timeout);
    }

    /** DLQ 에 들어간 이 회차 · 응모의 메시지. 받은 것은 큐에서 지운다. */
    Optional<Message> deadLetter(TestQueues queues, Duration timeout) {
        return queues.receive(DLQ, this::mine, timeout);
    }

    /** 돌려보내 draw-register 에 다시 들어간 메시지. 받은 것은 큐에서 지운다. */
    Optional<Message> resent(TestQueues queues, Duration timeout) {
        return queues.receive(QUEUE, this::mine, timeout);
    }

    static JsonNode body(Message message) {
        return JSON.readTree(message.body());
    }

    static Map<String, String> attributes(Message message) {
        Map<String, String> values = new HashMap<>();
        message.messageAttributes().forEach((name, value) -> values.put(name, value.stringValue()));
        return values;
    }

    private boolean mine(Message message) {
        return message.body().contains(drawId.toString()) || message.body().contains(entryId.toString());
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
