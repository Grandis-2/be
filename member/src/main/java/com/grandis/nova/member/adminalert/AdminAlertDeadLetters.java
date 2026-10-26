package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.sqs.SqsQueueUrls;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.HashMap;
import java.util.Map;

/**
 * 계약 위반 메시지를 큐의 DLQ(&lt;큐&gt;-dlq)에 바로 넣는다(회원 알림의 NotificationDeadLetters 와 같은 방식). 본문 · 받아 온 속성은 그대로 두고
 * 이유를 deadLetterReason 속성으로 더한다. Grafana 큐 메시지는 받아 온 속성이 없다(SNS 원문 전달). 넣으려면 그 DLQ 의 SendMessage 권한이 있어야 한다.
 */
class AdminAlertDeadLetters {

    static final String REASON = "deadLetterReason";

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final String deadLetterQueue;

    /** @param queue 본 큐 이름. DLQ 는 이름에 -dlq 를 붙인 큐다 */
    AdminAlertDeadLetters(SqsClient sqs, SqsQueueUrls queueUrls, String queue) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.deadLetterQueue = queue + "-dlq";
    }

    /** @throws RuntimeException 넣지 못했다 — 원본이 남아 늦춰 다시 받는다 */
    void send(Message message, AdminAlertRejected.Reason reason) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>(message.messageAttributes());
        attributes.put(REASON, MessageAttributeValue.builder().dataType("String").stringValue(reason.name()).build());
        sqs.sendMessage(request -> request.queueUrl(queueUrls.of(deadLetterQueue))
                .messageBody(message.body())
                .messageAttributes(attributes));
    }
}
