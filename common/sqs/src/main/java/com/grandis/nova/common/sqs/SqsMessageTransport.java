package com.grandis.nova.common.sqs;

import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.outbox.OutboundMessage;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 아웃박스를 SQS 로 보내는 전송(nova.outbox.transport=sqs). 목적지는 논리 이름이고 큐 URL 은 {@link SqsQueueUrls} 가 푼다.
 * 받는 쪽이 본문을 열지 않고 고를 수 있게 종류 · id 를 속성에 싣는다. 메시지가 준 추가 속성도 함께 싣는다.
 *
 * 호출마다 클라이언트의 apiCallTimeout 이 걸린다. 목적지를 처음 보낼 때(또는 큐 URL 을 못 받아 기억하지 못한 동안)는
 * 큐 URL 조회와 전송, 두 번 부른다 — 그래서 한 번 보내는 최대 시간은 제한 시간의 두 배다.
 */
class SqsMessageTransport implements MessageTransport {

    private static final String EVENT_TYPE = "eventType";
    private static final String EVENT_ID = "eventId";
    /** SQS 가 메시지 하나에 받는 속성 수. */
    private static final int MAX_ATTRIBUTES = 10;

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final Duration apiCallTimeout;

    SqsMessageTransport(SqsClient sqs, SqsQueueUrls queueUrls, Duration apiCallTimeout) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.apiCallTimeout = apiCallTimeout;
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
