package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.preorder.deadletter.DeadLetterRedriver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.Map;
import java.util.UUID;

/** DLQ 원문을 원래 큐로 다시 보낸다. 처리 결과를 그 행에 남기도록 행 id 를 메시지 속성에 싣는다. */
@Component
@ConditionalOnProperty(prefix = "nova.sqs", name = "region")
class SqsDeadLetterRedriver implements DeadLetterRedriver {

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;

    SqsDeadLetterRedriver(SqsClient sqs, SqsQueueUrls queueUrls) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
    }

    @Override
    public void redrive(String sourceQueue, String body, UUID deadLetterId) {
        sqs.sendMessage(request -> request
                .queueUrl(queueUrls.of(sourceQueue))
                .messageBody(body)
                .messageAttributes(Map.of(DEAD_LETTER_ID_ATTRIBUTE, MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(deadLetterId.toString())
                        .build())));
    }
}
