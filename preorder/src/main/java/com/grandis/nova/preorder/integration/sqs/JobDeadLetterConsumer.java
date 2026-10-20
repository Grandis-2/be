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
 * 어느 작업 · 실행 회차인지 읽을 수 없거나, 없는 작업 · 작업보다 앞선 실행 회차의 메시지는 지우지 않는다 — DLQ 에 남아 DLQ 경보로 사람이 본다.
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
        if (job.get().run() == null) {
            // 첫 실행으로 읽으면 재처리로 회차가 오른 작업의 메시지를 지난 실행으로 보고 지워, 작업이 PENDING 에 남는다
            log.error("실행 회차를 읽을 수 없는 작업 DLQ 메시지다 — 지우지 않는다 queue={} syncJobId={} messageId={}",
                    settings.queue(), job.get().syncJobId(), message.messageId());
            return;
        }
        try {
            if (!handler.onJobMessageDeadLettered(job.get().syncJobId(), job.get().run())) {
                log.error("작업에 반영할 수 없는 DLQ 메시지다(없는 작업 · 앞선 실행 회차) — 지우지 않는다 queue={} syncJobId={} "
                        + "messageId={}", settings.queue(), job.get().syncJobId(), message.messageId());
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
            UUID id = UUID.fromString(syncJobId.stringValue());
            JsonNode run = payload.get(RUN);
            if (run != null) {
                return Optional.of(new DeadJob(id, run.isInt() ? run.intValue() : null));
            }
            return Optional.of(new DeadJob(id, runAttribute(message)));
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 실행 회차가 본문에 없을 때. 속성도 없으면 첫 실행이고, 있는데 읽을 수 없으면 null(모름)이다. */
    private static Integer runAttribute(Message message) {
        MessageAttributeValue value = message.messageAttributes().get(RUN);
        if (value == null) {
            return FIRST_RUN;
        }
        try {
            return Integer.parseInt(value.stringValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @param run 실행 회차. 읽을 수 없으면 null */
    private record DeadJob(UUID syncJobId, Integer run) {
    }
}
