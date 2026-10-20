package com.grandis.nova.member.notification;

import com.grandis.nova.common.message.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * notification 큐의 메시지 하나를 받는 회원마다 한 행으로 저장한다. 저장과 지표 · 로그가 곧 Mock 발송이다.
 *
 * 계약에 맞지 않는 메시지는 {@link NotificationRejected} 로 올린다(DLQ 로 바로 간다). 저장 중 오류는 그대로 올려 다시 받게 한다 —
 * 둘을 섞으면 DB 순단 한 번에 멀쩡한 알림이 DLQ 로 가거나, 고칠 수 없는 메시지가 재수신 한도까지 헛돈다.
 * 같은 메시지를 다시 받으면 이미 넣은 회원은 건너뛴다({@link NotificationWriter}). 두 소비자가 동시에 넣다 진 쪽은 일시 오류로 끝나
 * 다시 받는다 — 그때는 모두 건너뛰어 중복으로 센다.
 */
@Component
public class NotificationReceiver {

    private static final Logger log = LoggerFactory.getLogger(NotificationReceiver.class);

    static final String EVENT_TYPE = "NOTIFICATION_REQUESTED";
    static final String METRIC = "member.notifications";
    /**
     * 지표 태그에 그대로 싣는 종류. kind 는 보내는 쪽이 정하는 문자열이라 그대로 태그로 쓰면 버그 하나로 시계열이 끝없이 는다 —
     * 모르는 종류는 OTHER 로 센다. 저장은 종류와 상관없이 한다(새 종류를 member 배포 없이 더할 수 있다).
     */
    static final Set<String> KNOWN_KINDS =
            Set.of("PREORDER_REGISTERED", "PREORDER_CANCELED", "DRAW_WON", "PREORDER_OPENED");
    static final String OTHER_KIND = "OTHER";
    /** 보낸 쪽 발생 시각부터 저장까지. 늘면 큐가 밀린 것이다(소비기 멈춤 · 처리량 부족) */
    static final String LAG_METRIC = "member.notifications.lag";
    static final int EVENT_ID_LENGTH = 36;
    static final int AGGREGATE_TYPE_LENGTH = 30;
    /** occurred_at 은 MySQL datetime(6) 이라 1000-01-01 ~ 9999-12-31 만 담긴다 */
    static final Instant EARLIEST_OCCURRED_AT = Instant.parse("1000-01-01T00:00:00Z");
    static final Instant OCCURRED_AT_LIMIT = Instant.parse("+10000-01-01T00:00:00Z");

    private final NotificationWriter writer;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    NotificationReceiver(NotificationWriter writer, JsonMapper jsonMapper, MeterRegistry meterRegistry, Clock clock) {
        this.writer = writer;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * @throws NotificationRejected 풀 수 없거나 계약에 맞지 않는 메시지
     * @throws RuntimeException     저장 중 오류(다시 받으면 될 수 있다)
     */
    public void receive(String body) {
        NotificationWriter.Incoming incoming = parse(body);
        int recipients = incoming.request().recipientIds().size();
        int stored = writer.store(incoming);
        String kind = kindTag(incoming.request().kind());
        meterRegistry.counter(METRIC, "outcome", "stored", "kind", kind).increment(stored);
        meterRegistry.counter(METRIC, "outcome", "duplicate", "kind", kind).increment(recipients - stored);
        if (stored > 0) {
            meterRegistry.timer(LAG_METRIC).record(lag(incoming.occurredAt(), incoming.receivedAt()));
        }
        log.info("알림 받음(Mock 발송) eventId={} kind={} recipients={} stored={} duplicate={}", incoming.eventId(),
                incoming.request().kind(), recipients, stored, recipients - stored);
    }

    private NotificationWriter.Incoming parse(String body) {
        EventEnvelope envelope = readEnvelope(body);
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            throw new NotificationRejected(NotificationRejected.Reason.NOT_NOTIFICATION,
                    "알림 요청이 아니다: eventType=" + envelope.eventType());
        }
        String eventId = envelope.eventId();
        // 칸에 담기지 않는 값(긴 aggregateType · datetime(6) 범위 밖 시각)은 INSERT 가 DB 오류로 끝나 일시 오류처럼 다시 받다 이유 없이
        // DLQ 로 간다 — 여기서 계약 위반으로 거른다. aggregateType 은 추적용이라 없어도 받는다
        Instant occurredAt = envelope.occurredAt();
        if (eventId == null || eventId.isBlank() || eventId.length() > EVENT_ID_LENGTH || occurredAt == null
                || occurredAt.isBefore(EARLIEST_OCCURRED_AT) || !occurredAt.isBefore(OCCURRED_AT_LIMIT)
                || envelope.aggregateType() != null && envelope.aggregateType().length() > AGGREGATE_TYPE_LENGTH) {
            throw new NotificationRejected(NotificationRejected.Reason.INVALID_ENVELOPE,
                    "봉투의 eventId · occurredAt · aggregateType 이 계약에 맞지 않는다: eventId=" + eventId);
        }
        JsonNode payload = envelope.payload();
        if (payload == null || !payload.isObject()) {
            throw new NotificationRejected(NotificationRejected.Reason.INVALID_PAYLOAD, "payload 가 없다: eventId=" + eventId);
        }
        NotificationRequested request = readPayload(payload, eventId);
        return new NotificationWriter.Incoming(eventId, envelope.aggregateType(), envelope.aggregateId(), envelope.occurredAt(),
                request, clock.instant());
    }

    /** 본문이 JSON 리터럴 null 이면 Jackson 은 예외 없이 null 을 돌려준다 — 그것도 풀 수 없는 것으로 본다. */
    private EventEnvelope readEnvelope(String body) {
        EventEnvelope envelope;
        try {
            envelope = jsonMapper.readValue(body, EventEnvelope.class);
        } catch (JacksonException e) {
            throw new NotificationRejected(NotificationRejected.Reason.UNREADABLE, "봉투를 풀 수 없다", e);
        }
        if (envelope == null) {
            throw new NotificationRejected(NotificationRejected.Reason.UNREADABLE, "본문이 null 이다");
        }
        return envelope;
    }

    static String kindTag(String kind) {
        return KNOWN_KINDS.contains(kind) ? kind : OTHER_KIND;
    }

    /** 칸 검증은 레코드 생성자가 한다 — Jackson 이 그 예외를 감싸 올린다. 모르는 칸은 무시한다(보내는 쪽이 칸을 더해도 깨지지 않게). */
    private NotificationRequested readPayload(JsonNode payload, String eventId) {
        try {
            return jsonMapper.treeToValue(payload, NotificationRequested.class);
        } catch (JacksonException e) {
            throw new NotificationRejected(NotificationRejected.Reason.INVALID_PAYLOAD,
                    "payload 가 계약에 맞지 않는다: eventId=" + eventId, e);
        }
    }

    /** 보낸 쪽 시계가 앞서 음수가 나오면 0 으로 본다. */
    private static Duration lag(Instant occurredAt, Instant storedAt) {
        Duration lag = Duration.between(occurredAt, storedAt);
        return lag.isNegative() ? Duration.ZERO : lag;
    }
}
