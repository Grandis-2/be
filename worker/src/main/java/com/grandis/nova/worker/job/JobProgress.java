package com.grandis.nova.worker.job;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 작업이 어디까지 왔는지. worker 는 상태를 저장하지 않고 메시지 속성에 싣고 다닌다.
 *
 * @param run         작업의 몇 번째 실행인가(1부터). preorder 가 정해 보내고 worker 는 그대로 이어 결과에 싣는다
 * @param attempt     이번 세대의 몇 번째 차례(1부터)
 * @param generation  몇 번째 세대(1부터)
 * @param lastOutcome 직전 차례의 결과. 없으면 null
 */
public record JobProgress(int run, int attempt, int generation, AttemptResult lastOutcome, Actor actor) {

    static final String RUN = "run";
    static final String ATTEMPT = "attempt";
    static final String GENERATION = "generation";
    static final String LAST_OUTCOME = "lastOutcome";
    static final String ACTOR = "actor";
    /** 설정 오류로 처음 돌려보낸 시각(ISO-8601). 차례가 아니라 시간으로 포기를 정한다 — 작업 · 드로우 메시지가 함께 쓴다. */
    public static final String MISCONFIGURED_SINCE = "misconfiguredSince";
    /** 소비기가 받아 올 속성 이름. 적지 않은 속성은 받은 메시지에 없고 지연 재전송 때 이어지지 않는다. */
    public static final List<String> ATTRIBUTE_NAMES =
            List.of(RUN, ATTEMPT, GENERATION, LAST_OUTCOME, ACTOR, MISCONFIGURED_SINCE, "eventType", "eventId");

    public JobProgress {
        if (run < 1 || attempt < 1 || generation < 1 || actor == null) {
            throw new IllegalArgumentException(
                    "run · attempt · generation 은 1 이상, actor 는 필수다: run=%d attempt=%d generation=%d actor=%s"
                            .formatted(run, attempt, generation, actor));
        }
    }

    /**
     * 속성이 없거나 읽을 수 없으면 처음으로 본다(첫 실행 · 1세대 1차례 · SYSTEM · 직전 결과 없음). 같은 키로 다시 보내도 외부가 한 번만
     * 처리하므로 안전하고, 읽지 못한다고 버리면 작업을 잃는다.
     */
    public static JobProgress of(Message message) {
        Map<String, MessageAttributeValue> attributes = message.messageAttributes();
        return new JobProgress(count(attributes, RUN), count(attributes, ATTEMPT), count(attributes, GENERATION),
                named(AttemptResult.class, attributes, LAST_OUTCOME, null),
                named(Actor.class, attributes, ACTOR, Actor.SYSTEM));
    }

    /** 설정 오류로 처음 돌려보낸 시각. 없거나 읽을 수 없으면 비어 있다(지금 처음 본 것으로 한다). */
    public static Optional<Instant> misconfiguredSince(Message message) {
        String value = text(message.messageAttributes(), MISCONFIGURED_SINCE);
        try {
            return value == null ? Optional.empty() : Optional.of(Instant.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** 지연 재전송에 넘길 속성. 직전 결과가 없으면 그 속성을 뺀다(값 null). 차례를 썼으니 설정 오류 시각도 뺀다. */
    public Map<String, String> toAttributes() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put(RUN, Integer.toString(run));
        attributes.put(ATTEMPT, Integer.toString(attempt));
        attributes.put(GENERATION, Integer.toString(generation));
        attributes.put(LAST_OUTCOME, lastOutcome == null ? null : lastOutcome.name());
        attributes.put(ACTOR, actor.name());
        attributes.put(MISCONFIGURED_SINCE, null);
        return attributes;
    }

    private static int count(Map<String, MessageAttributeValue> attributes, String name) {
        String value = text(attributes, name);
        try {
            return value == null ? 1 : Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static <E extends Enum<E>> E named(Class<E> type, Map<String, MessageAttributeValue> attributes,
                                               String name, E fallback) {
        String value = text(attributes, name);
        try {
            return value == null ? fallback : Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String text(Map<String, MessageAttributeValue> attributes, String name) {
        MessageAttributeValue value = attributes.get(name);
        return value == null ? null : value.stringValue();
    }
}
