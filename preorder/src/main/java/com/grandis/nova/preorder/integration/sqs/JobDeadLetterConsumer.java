package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.sqs.QueuePoller;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.preorder.event.PreorderEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * worker 로 보낸 작업 메시지(등록 · 취소)의 DLQ 를 받아, 그 작업을 DEAD_LETTER 로 둔다. 보낸 쪽인 preorder 가 자기 작업 표에 결과를
 * 남길 뿐 다시 보내지 않는다 — 다시 돌리는 일은 관리자 재처리가 실행 회차를 올려 새 메시지로 한다. 반영이 커밋된 뒤에만 지운다.
 * 어느 작업인지 읽을 수 없거나 없는 작업의 메시지는 지우지 않는다 — DLQ 에 남아 DLQ 경보로 사람이 본다.
 */
class JobDeadLetterConsumer extends QueuePoller {

    private static final Logger log = LoggerFactory.getLogger(JobDeadLetterConsumer.class);
    /** 실행 회차. 보낼 때 payload 의 값을 속성으로도 싣는다(JobAttributes). payload 에 없을 때 쓴다. */
    private static final String RUN = "run";
    private static final int FIRST_RUN = 1;

    private final PreorderEventHandler handler;
    private final JsonMapper json;

    JobDeadLetterConsumer(SqsClient sqs, SqsQueueUrls queueUrls, JobDeadLetterConsumerProperties properties,
                          String queue, PreorderEventHandler handler, JsonMapper json) {
        super(sqs, queueUrls, properties.toSettings(queue), List.of(RUN));
        this.handler = handler;
        this.json = json;
    }

    @Override
    protected void handle(String queueUrl, Message message) {
        Optional<DeadJob> job = jobOf(message);
        if (job.isEmpty()) {
            log.error("어느 작업인지 읽을 수 없는 작업 DLQ 메시지다 — 지우지 않는다 queue={} messageId={}", settings.queue(),
                    message.messageId());
            return;
        }
        try {
            if (!handler.onJobMessageDeadLettered(job.get().syncJobId(), job.get().run())) {
                log.error("없는 작업의 메시지가 DLQ 로 왔다 — 지우지 않는다 queue={} syncJobId={} messageId={}", settings.queue(),
                        job.get().syncJobId(), message.messageId());
                return;
            }
        } catch (RuntimeException e) {
            log.warn("작업 DLQ 메시지를 반영하지 못했다 — 가시성 시간 뒤 다시 받는다 syncJobId={} messageId={}",
                    job.get().syncJobId(), message.messageId(), e);
            return;
        }
        try {
            sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.warn("반영한 작업 DLQ 메시지를 지우지 못했다 — 다시 받아도 같은 결과다 syncJobId={} messageId={}",
                    job.get().syncJobId(), message.messageId(), e);
        }
    }

    private Optional<DeadJob> jobOf(Message message) {
        try {
            JsonNode payload = json.readTree(message.body()).get("payload");
            JsonNode syncJobId = payload == null ? null : payload.get("syncJobId");
            if (syncJobId == null || !syncJobId.isString()) {
                return Optional.empty();
            }
            JsonNode run = payload.get(RUN);
            return Optional.of(new DeadJob(UUID.fromString(syncJobId.stringValue()),
                    run != null && run.isInt() ? run.intValue() : runAttribute(message)));
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static int runAttribute(Message message) {
        MessageAttributeValue value = message.messageAttributes().get(RUN);
        try {
            return value == null ? FIRST_RUN : Integer.parseInt(value.stringValue());
        } catch (NumberFormatException e) {
            return FIRST_RUN;
        }
    }

    private record DeadJob(UUID syncJobId, int run) {
    }
}
