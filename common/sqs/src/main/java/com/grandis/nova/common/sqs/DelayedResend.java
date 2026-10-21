package com.grandis.nova.common.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 받은 메시지를 같은 큐에 지연을 두고 다시 보낸다. 본문은 그대로, 받은 속성에 호출자가 준 변경(재시도 횟수 등)을 덮어쓴다.
 * 받은 MessageGroupId 도 잇는다 — 빠지면 다시 보낸 메시지가 공정 큐의 묶음에서 벗어난다.
 * 원본은 지우지 않는다 — 다시 보낸 뒤 호출자가 지우고, 보내지 못하면 던지므로 원본이 남아 다시 받는다.
 * 원본을 지우지 못하면 사본이 하나 더 돈다. 받는 쪽 처리는 멱등이어야 한다.
 */
public class DelayedResend {

    private static final Logger log = LoggerFactory.getLogger(DelayedResend.class);
    /** SQS DelaySeconds 상한. */
    public static final Duration MAX_DELAY = Duration.ofMinutes(15);

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final String queue;

    public DelayedResend(SqsClient sqs, SqsQueueUrls queueUrls, String queue) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.queue = queue;
    }

    /**
     * @param message    받은 메시지. 소비기가 받아 온 속성만 이어 보낸다(적지 않은 속성은 받은 메시지에 없다)
     * @param attributes 덮어쓸 속성(문자열로 싣는다). 값이 null 이면 그 속성을 뺀다
     * @param delay      다시 보일 때까지의 지연, 0 ~ 15분. 1초 미만은 올린다
     * @throws IllegalArgumentException delay 가 범위 밖
     * @throws RuntimeException         다시 보내지 못했다(원본은 남는다)
     */
    public void resend(Message message, Map<String, String> attributes, Duration delay) {
        if (delay == null || delay.isNegative() || delay.compareTo(MAX_DELAY) > 0) {
            throw new IllegalArgumentException("지연 재전송의 지연은 0 ~ 15m 여야 한다: " + delay);
        }
        Map<String, MessageAttributeValue> carried = new HashMap<>(message.messageAttributes());
        attributes.forEach((name, value) -> {
            if (value == null) {
                carried.remove(name);
            } else {
                carried.put(name, MessageAttributeValue.builder().dataType("String").stringValue(value).build());
            }
        });
        int delaySeconds = (int) delay.getSeconds() + (delay.getNano() > 0 ? 1 : 0);
        try {
            sqs.sendMessage(request -> request.queueUrl(queueUrls.of(queue))
                    .messageBody(message.body())
                    .delaySeconds(delaySeconds)
                    .messageAttributes(carried)
                    .messageGroupId(message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID)));
        } catch (RuntimeException e) {
            log.error("지연을 두고 다시 보내지 못했다 — 큐 권한 · 설정 확인 필요(원본을 다시 받는다) queue={} messageId={}",
                    queue, message.messageId(), e);
            throw e;
        }
        log.debug("지연을 두고 다시 보냈다 queue={} messageId={} delaySeconds={}", queue, message.messageId(), delaySeconds);
    }
}
