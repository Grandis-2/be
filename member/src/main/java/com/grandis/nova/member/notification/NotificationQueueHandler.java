package com.grandis.nova.member.notification;

import com.grandis.nova.common.sqs.QueueMessageHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * notification 큐의 메시지 하나를 처리한다. 실패를 둘로 가른다.
 * - 계약 위반({@link NotificationRejected}): 다시 받아도 같다. DLQ 에 이유를 붙여 바로 넣고 돌아온다(소비기가 원본을 지운다).
 * - 그 밖(DB 순단 · 시간 초과 등): 던진다. common:sqs 가 늦춰 다시 받고, 재수신 한도를 넘기면 재드라이브 정책이 DLQ 로 옮긴다.
 * DLQ 에 넣지 못하면 던진다 — 원본이 남아 다시 받으니 계약 위반 메시지도 잃지 않는다.
 */
class NotificationQueueHandler implements QueueMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationQueueHandler.class);

    static final String REJECTED_METRIC = "member.notifications.rejected";

    private final NotificationReceiver receiver;
    private final NotificationDeadLetters deadLetters;
    private final MeterRegistry meterRegistry;

    NotificationQueueHandler(NotificationReceiver receiver, NotificationDeadLetters deadLetters,
                             MeterRegistry meterRegistry) {
        this.receiver = receiver;
        this.deadLetters = deadLetters;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void handle(Message message) {
        try {
            receiver.receive(message.body());
        } catch (NotificationRejected e) {
            deadLetters.send(message, e.reason());
            meterRegistry.counter(REJECTED_METRIC, "reason", e.reason().name()).increment();
            // 원인(Jackson) 메시지는 payload 조각을 되풀어 쓸 수 있어 ERROR 에는 클래스만 싣고, 스택은 DEBUG 로만 남긴다
            Throwable cause = e.getCause();
            log.error("계약에 맞지 않는 알림 요청을 DLQ 로 옮겼다 — 보내는 쪽을 고친다 messageId={} reason={} detail={} cause={}",
                    message.messageId(), e.reason(), e.getMessage(), cause == null ? null : cause.getClass().getSimpleName());
            log.debug("알림 요청 거절 상세 messageId={}", message.messageId(), e);
        }
    }
}
