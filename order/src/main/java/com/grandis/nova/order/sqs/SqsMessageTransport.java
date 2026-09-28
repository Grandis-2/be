package com.grandis.nova.order.sqs;

import com.grandis.nova.order.outbox.MessageTransport;
import com.grandis.nova.order.outbox.OutboundMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.Map;

/**
 * SQS 로 보내는 전송(nova.outbox.transport=sqs). 제한 시간은 클라이언트의 apiCallTimeout 이다.
 * 목적지는 논리 이름({@code OutboundEventType.destination()})이고 큐 URL 은 {@link SqsQueueUrls} 가 푼다.
 * 설정 · 큐 URL 이 package-private 이라 이 패키지에 둔다.
 *
 * common:outbox 이전 시: preorder sqs.SqsMessageTransport 의 복사본이다. 그대로 공통으로 옮긴다.
 */
@Component
@ConditionalOnProperty(name = "nova.outbox.transport", havingValue = "sqs")
class SqsMessageTransport implements MessageTransport {

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;

    SqsMessageTransport(SqsClient sqs, SqsQueueUrls queueUrls) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
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
