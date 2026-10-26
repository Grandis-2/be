package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.sqs.QueueMessageHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.function.Consumer;

/**
 * 관리자 알림 큐의 메시지 하나를 처리한다. 실패를 둘로 가른다(회원 알림의 NotificationQueueHandler 와 같다).
 * - 계약 위반({@link AdminAlertRejected}): DLQ 에 이유를 붙여 바로 넣고 돌아온다(소비기가 원본을 지운다).
 * - 그 밖(DB 순단 등): 던진다. common:sqs 가 늦춰 다시 받고, 재수신 한도를 넘기면 재드라이브 정책이 DLQ 로 옮긴다.
 */
class AdminAlertQueueHandler implements QueueMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertQueueHandler.class);

    static final String REJECTED_METRIC = "member.admin-alerts.rejected";

    private final String queue;
    private final Consumer<Message> receiver;
    private final AdminAlertDeadLetters deadLetters;
    private final MeterRegistry meterRegistry;

    AdminAlertQueueHandler(String queue, Consumer<Message> receiver, AdminAlertDeadLetters deadLetters, MeterRegistry meterRegistry) {
        this.queue = queue;
        this.receiver = receiver;
        this.deadLetters = deadLetters;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void handle(Message message) {
        try {
            receiver.accept(message);
        } catch (AdminAlertRejected e) {
            deadLetters.send(message, e.reason());
            meterRegistry.counter(REJECTED_METRIC, "queue", queue, "reason", e.reason().name()).increment();
            // 원인(Jackson) 메시지는 본문 조각을 되풀어 쓸 수 있어 ERROR 에는 클래스만 싣는다
            Throwable cause = e.getCause();
            log.error("계약에 맞지 않는 관리자 알림 메시지를 DLQ 로 옮겼다 — 보내는 쪽을 고친다 queue={} messageId={} reason={} detail={} cause={}",
                    queue, message.messageId(), e.reason(), e.getMessage(), cause == null ? null : cause.getClass().getSimpleName());
            log.debug("관리자 알림 거절 상세 messageId={}", message.messageId(), e);
        }
    }
}
