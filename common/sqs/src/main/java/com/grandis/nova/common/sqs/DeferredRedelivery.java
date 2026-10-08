package com.grandis.nova.common.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 지금 결과를 정할 수 없는 메시지(다른 서비스의 결과 · 앞선 이벤트를 기다리는 것)를 늦춰 다시 받는다.
 * 같은 본문을 같은 큐에 DelaySeconds 로 다시 보내고, 원본은 소비기가 정상 반환으로 지운다 — 실패로 늦추면 재수신 횟수가 쌓여 몇 분 만에
 * DLQ 로 가지만, 새로 보낸 메시지는 횟수가 새로 시작한다. 받은 메시지 속성은 그대로 이어 보낸다.
 *
 * 포기하지 않는다. 지연은 보류할 때마다 두 배(첫 지연부터 최대 지연까지, SQS 상한 15분)이고,
 * 처음 보류한 뒤 경보 기준이 지나면 ERROR 를 한 번만 남긴다(속성으로 이어 받는다). 끝내 풀리지 않는 메시지는 사람이 본다.
 *
 * 다시 보내기가 실패하면 ERROR 로 올리고 예외를 던진다 — 원본이 지워지지 않아 다시 받는다(권한 · 큐 설정 오류가 첫 건에서 보이게).
 * 다시 보낸 뒤 원본을 지우지 못하면 사본이 하나 더 돈다. 받는 쪽 처리는 멱등이어야 한다.
 */
public class DeferredRedelivery {

    private static final Logger log = LoggerFactory.getLogger(DeferredRedelivery.class);

    static final String DEFER_COUNT = "deferCount";
    static final String FIRST_DEFERRED_AT = "firstDeferredAt";
    static final String ALERTED = "deferAlerted";
    /** 다시 보낼 때 이어 받는 속성. 소비기가 이 이름들을 받아 오게 한다(적지 않은 속성은 받은 메시지에 없다). */
    public static final List<String> ATTRIBUTES =
            List.of(DEFER_COUNT, FIRST_DEFERRED_AT, ALERTED, "eventType", "eventId");

    private final SqsClient sqs;
    private final SqsQueueUrls queueUrls;
    private final String queue;
    private final Settings settings;
    private final Clock clock;

    public DeferredRedelivery(SqsClient sqs, SqsQueueUrls queueUrls, String queue, Settings settings, Clock clock) {
        this.sqs = sqs;
        this.queueUrls = queueUrls;
        this.queue = queue;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * 처리 함수를 소비기용 처리기로 감싼다. {@link MessageHandling#DEFER} 를 돌려주면 늦춰 다시 보내고, 원본은 소비기가 지운다.
     * 처리 함수가 던지면 그대로 올린다(소비기가 늦춰 다시 받다가 한도를 넘으면 DLQ).
     */
    public QueueMessageHandler handler(Function<Message, MessageHandling> handling) {
        return message -> {
            if (handling.apply(message) == MessageHandling.DEFER) {
                redeliver(message);
            }
        };
    }

    /** @throws RuntimeException 다시 보내지 못했다(원본은 남는다) */
    public void redeliver(Message message) {
        Map<String, MessageAttributeValue> attributes = message.messageAttributes();
        int deferCount = deferCountOf(attributes);
        Instant now = clock.instant();
        Instant firstDeferredAt = firstDeferredAtOf(attributes, now);
        Map<String, MessageAttributeValue> carried = new HashMap<>(attributes);
        if (!now.isBefore(firstDeferredAt.plus(settings.alertAfter())) && !attributes.containsKey(ALERTED)) {
            log.error("메시지 보류가 길어진다 — 사람 확인 필요(계속 늦춰 다시 받는다) queue={} messageId={} firstDeferredAt={} "
                    + "deferCount={}", queue, message.messageId(), firstDeferredAt, deferCount);
            carried.put(ALERTED, text("true"));
        }
        Duration delay = settings.delayFor(deferCount);
        carried.put(DEFER_COUNT, number(deferCount + 1));
        carried.put(FIRST_DEFERRED_AT, text(firstDeferredAt.toString()));
        try {
            sqs.sendMessage(request -> request.queueUrl(queueUrls.of(queue))
                    .messageBody(message.body())
                    .delaySeconds((int) delay.toSeconds())
                    .messageAttributes(carried));
        } catch (RuntimeException e) {
            log.error("늦춰 다시 보내지 못했다 — 큐 권한 · 설정 확인 필요(원본을 다시 받는다) queue={} messageId={}",
                    queue, message.messageId(), e);
            throw e;
        }
        log.info("결과를 정할 수 없어 늦춰 다시 받는다 queue={} messageId={} delay={} deferCount={}",
                queue, message.messageId(), delay, deferCount + 1);
    }

    private static int deferCountOf(Map<String, MessageAttributeValue> attributes) {
        MessageAttributeValue value = attributes.get(DEFER_COUNT);
        if (value == null || value.stringValue() == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.stringValue()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 처음 보류하는 메시지이거나 속성을 읽을 수 없으면 지금부터 잰다(상한이 늦어질 뿐 끝은 난다). */
    private static Instant firstDeferredAtOf(Map<String, MessageAttributeValue> attributes, Instant now) {
        MessageAttributeValue value = attributes.get(FIRST_DEFERRED_AT);
        if (value == null || value.stringValue() == null) {
            return now;
        }
        try {
            return Instant.parse(value.stringValue());
        } catch (DateTimeParseException e) {
            return now;
        }
    }

    private static MessageAttributeValue number(int value) {
        return MessageAttributeValue.builder().dataType("Number").stringValue(Integer.toString(value)).build();
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    /**
     * @param firstDelay 첫 보류의 지연. 보류할 때마다 두 배
     * @param maxDelay   지연 상한. SQS DelaySeconds 상한(15분) 이하
     * @param alertAfter 처음 보류한 뒤 이만큼 지나면 ERROR 를 한 번 남긴다. 경보일 뿐 메시지는 계속 돈다 — 결제의 환불 상한(첫 전송
     *                   + 24h)과 기준 시각이 달라도 결과를 잃지 않는다
     */
    public record Settings(Duration firstDelay, Duration maxDelay, Duration alertAfter) {

        static final Duration SQS_MAX_DELAY = Duration.ofMinutes(15);

        public Settings {
            if (firstDelay.toSeconds() < 1 || maxDelay.compareTo(firstDelay) < 0 || maxDelay.compareTo(SQS_MAX_DELAY) > 0
                    || alertAfter.compareTo(maxDelay) < 0) {
                throw new IllegalArgumentException(
                        "지연 재발행은 1s ≤ first ≤ max ≤ 15m, max ≤ alertAfter 여야 한다: first=%s max=%s alertAfter=%s"
                                .formatted(firstDelay, maxDelay, alertAfter));
            }
        }

        Duration delayFor(int deferCount) {
            Duration delay = firstDelay.multipliedBy(1L << Math.min(deferCount, 20));
            return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
        }
    }
}
