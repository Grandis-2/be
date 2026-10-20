package com.grandis.nova.common.sqs;

import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.outbox.OutboundMessage;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 아웃박스를 SQS 로 보내는 전송(nova.outbox.transport=sqs). 목적지는 논리 이름이고 큐 URL 은 {@link SqsQueueUrls} 가 푼다.
 * 받는 쪽이 본문을 열지 않고 고를 수 있게 종류 · id 를 속성에 싣는다. 메시지가 준 추가 속성도 함께 싣는다.
 *
 * 호출마다 클라이언트의 apiCallTimeout 이 걸린다. 목적지를 처음 보낼 때(또는 큐 URL 을 못 받아 기억하지 못한 동안)는
 * 큐 URL 조회와 전송, 두 번 부른다 — 그래서 한 번 보내는 최대 시간은 제한 시간의 두 배다.
 *
 * 묶음은 SendMessageBatch 한 번(10건 · 합계 크기 한도까지)이다. 한도를 넘는 묶음은 나눠 부르고, 결과는 건마다 돌려준다.
 * 아웃박스 메시지는 수 KB 라 실제로는 한 번이다.
 */
class SqsMessageTransport implements MessageTransport {

    private static final String EVENT_TYPE = "eventType";
    private static final String EVENT_ID = "eventId";
    /** SQS 가 메시지 하나에 받는 속성 수. */
    private static final int MAX_ATTRIBUTES = 10;
    /** SendMessageBatch 한 번의 건수 한도. */
    static final int MAX_BATCH = 10;
    /** SendMessageBatch 한 번의 합계 크기(본문 + 속성). SQS 의 기본 메시지 크기 한도 256 KiB 에 맞춘다. */
    static final long MAX_BATCH_BYTES = 256 * 1024;

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final Duration apiCallTimeout;
    private final long maxBatchBytes;

    SqsMessageTransport(SqsClient sqs, SqsQueueUrls queueUrls, Duration apiCallTimeout) {
        this(sqs, queueUrls, apiCallTimeout, MAX_BATCH_BYTES);
    }

    SqsMessageTransport(SqsClient sqs, SqsQueueUrls queueUrls, Duration apiCallTimeout, long maxBatchBytes) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.apiCallTimeout = apiCallTimeout;
        this.maxBatchBytes = maxBatchBytes;
    }

    @Override
    public Duration maxSendTime() {
        return apiCallTimeout.multipliedBy(2);
    }

    @Override
    public void send(OutboundMessage message) {
        Map<String, MessageAttributeValue> attributes = attributesOf(message);
        sqs.sendMessage(request -> request
                .queueUrl(queueUrls.of(message.destination()))
                .messageBody(message.body())
                .messageAttributes(attributes));
    }

    @Override
    public int maxBatchSize() {
        return MAX_BATCH;
    }

    /** 같은 목적지 메시지만 받는다(아웃박스 발행기가 목적지별로 묶는다). 속성이 잘못된 건은 그 건만 실패다. */
    @Override
    public Map<Integer, RuntimeException> sendAll(List<OutboundMessage> messages) {
        Map<Integer, RuntimeException> failures = new HashMap<>();
        if (messages.isEmpty()) {
            return failures;
        }
        String destination = messages.getFirst().destination();
        List<Integer> indexes = new ArrayList<>();
        List<SendMessageBatchRequestEntry> entries = new ArrayList<>();
        long bytes = 0;
        for (int i = 0; i < messages.size(); i++) {
            OutboundMessage message = messages.get(i);
            if (!message.destination().equals(destination)) {
                throw new IllegalArgumentException("묶음은 목적지 하나여야 한다: " + destination + " · " + message.destination());
            }
            Map<String, MessageAttributeValue> attributes;
            try {
                attributes = attributesOf(message);
            } catch (IllegalArgumentException e) {
                failures.put(i, e);
                continue;
            }
            long size = sizeOf(message.body(), attributes);
            if (!entries.isEmpty() && (entries.size() == MAX_BATCH || bytes + size > maxBatchBytes)) {
                sendBatch(destination, indexes, entries, failures);
                indexes = new ArrayList<>();
                entries = new ArrayList<>();
                bytes = 0;
            }
            indexes.add(i);
            entries.add(SendMessageBatchRequestEntry.builder()
                    .id(Integer.toString(i))
                    .messageBody(message.body())
                    .messageAttributes(attributes)
                    .build());
            bytes += size;
        }
        if (!entries.isEmpty()) {
            sendBatch(destination, indexes, entries, failures);
        }
        return failures;
    }

    /** 한 번 부른다. 호출이 실패하면 그 묶음의 건이 모두 실패고, 앞서 보낸 묶음은 그대로 성공이다. */
    private void sendBatch(String destination, List<Integer> indexes, List<SendMessageBatchRequestEntry> entries,
                           Map<Integer, RuntimeException> failures) {
        SendMessageBatchResponse response;
        try {
            response = sqs.sendMessageBatch(request -> request.queueUrl(queueUrls.of(destination)).entries(entries));
        } catch (RuntimeException e) {
            indexes.forEach(index -> failures.put(index, e));
            return;
        }
        for (BatchResultErrorEntry failed : response.failed()) {
            failures.put(Integer.parseInt(failed.id()), new IllegalStateException(
                    "SQS 묶음 전송 실패 code=" + failed.code() + " senderFault=" + failed.senderFault()
                            + " message=" + failed.message()));
        }
    }

    /** SQS 가 세는 크기 — 본문과 속성 이름 · 형 · 값. */
    private static long sizeOf(String body, Map<String, MessageAttributeValue> attributes) {
        long size = utf8(body);
        for (Map.Entry<String, MessageAttributeValue> attribute : attributes.entrySet()) {
            size += utf8(attribute.getKey()) + utf8(attribute.getValue().dataType())
                    + utf8(attribute.getValue().stringValue());
        }
        return size;
    }

    private static long utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, MessageAttributeValue> attributesOf(OutboundMessage message) {
        Map<String, String> extra = message.attributes();
        if (extra.containsKey(EVENT_TYPE) || extra.containsKey(EVENT_ID) || extra.size() > MAX_ATTRIBUTES - 2) {
            throw new IllegalArgumentException("추가 속성은 " + EVENT_TYPE + " · " + EVENT_ID + " 를 뺀 "
                    + (MAX_ATTRIBUTES - 2) + "개까지다: " + extra.keySet());
        }
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        extra.forEach((name, value) -> attributes.put(name, text(value)));
        attributes.put(EVENT_TYPE, text(message.eventType()));
        attributes.put(EVENT_ID, text(message.eventId()));
        return attributes;
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
