package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.SqsQueueUrls;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.HashMap;
import java.util.Map;

/**
 * 다시 보내도 같은 거절을 받은 메시지를 큐의 DLQ(&lt;큐&gt;-dlq)에 바로 넣는다 — 큐의 최대 수신 횟수까지 헛돌지 않게.
 * 본문 · 받아 온 속성은 그대로 두고 이유를 속성으로 더한다. 원본은 넣은 뒤 부르는 쪽이 지운다. 넣지 못하면 던진다(원본은 남는다).
 */
final class DeadLetterSender {

    private static final String REASON = "deadLetterReason";

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final String queue;

    /** @param queue 본 큐 이름. DLQ 는 이름에 -dlq 를 붙인 큐다(인프라 · docker/floci/create-queues.sh) */
    DeadLetterSender(SqsClient sqs, SqsQueueUrls queueUrls, String queue) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.queue = queue + "-dlq";
    }

    /** @throws RuntimeException 넣지 못했다(원본은 남는다) */
    void send(Message message, String reason) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>(message.messageAttributes());
        attributes.put(REASON, MessageAttributeValue.builder().dataType("String").stringValue(reason).build());
        sqs.sendMessage(request -> request.queueUrl(queueUrls.of(queue))
                .messageBody(message.body())
                .messageAttributes(attributes));
    }
}
