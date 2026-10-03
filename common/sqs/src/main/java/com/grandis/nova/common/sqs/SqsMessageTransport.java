package com.grandis.nova.common.sqs;

import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.outbox.OutboundMessage;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Duration;
import java.util.Map;

/**
 * 아웃박스를 SQS 로 보내는 전송(nova.outbox.transport=sqs). 목적지는 논리 이름이고 큐 URL 은 {@link SqsQueueUrls} 가 푼다.
 * 받는 쪽이 본문을 열지 않고 고를 수 있게 종류 · id 를 속성에 싣는다.
 *
 * 호출마다 클라이언트의 apiCallTimeout 이 걸린다. 목적지를 처음 보낼 때(또는 큐 URL 을 못 받아 기억하지 못한 동안)는
 * 큐 URL 조회와 전송, 두 번 부른다 — 그래서 한 번 보내는 최대 시간은 제한 시간의 두 배다.
 */
class SqsMessageTransport implements MessageTransport {

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
        sqs.sendMessage(request -> request
                .queueUrl(queueUrls.of(message.destination()))
                .messageBody(message.body())
                .messageAttributes(Map.of(
                        "eventType", text(message.eventType()),
                        "eventId", text(message.eventId()))));
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
