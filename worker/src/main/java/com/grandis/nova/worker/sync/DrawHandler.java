package com.grandis.nova.worker.sync;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.BatchMessageHandler;
import com.grandis.nova.common.sqs.DelayedResend;
import com.grandis.nova.common.sqs.MessageOutcome;
import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.event.DrawResultReady;
import com.grandis.nova.worker.job.DrawEntryPaid;
import com.grandis.nova.worker.job.DrawEventType;
import com.grandis.nova.worker.job.DrawRequested;
import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobType;
import com.grandis.nova.worker.mock.ConcurrencyLimiter;
import com.grandis.nova.worker.mock.ConcurrencyLimiter.Permit;
import com.grandis.nova.worker.mock.MockCircuit;
import com.grandis.nova.worker.mock.MockGateway;
import com.grandis.nova.worker.mock.MockResult;
import com.grandis.nova.worker.mock.MockResult.Skipped;
import com.grandis.nova.worker.mock.RejectGuard;
import com.grandis.nova.worker.mock.Verdict;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 럭키드로우 메시지(draw-register) 하나를 처리한다 — be 계약 contracts/draw-entry-sync.md. 상태를 저장하지 않는다. Mock 이 응모 id ·
 * 회차 id 로 같은 요청을 재생하므로, 결과를 모르면 조회 없이 같은 요청을 다시 보낸다.
 * <ul>
 *   <li>응모(DRAW_ENTRY_PAID): Mock 에 넘긴다. order 에는 알리지 않는다. 마감이 지났어도 거절하지 않는다(추첨 전이면 Mock 이 받는다).</li>
 *   <li>추첨(DRAW_REQUESTED): Mock 이 받은 응모 수가 결제 완료 수와 같을 때만 추첨을 맡기고(기대 수를 함께 보내 Mock 이 잠근 채 다시
 *   센다) 결과를 order-events 로 보낸다. 다르면 늦은 응모가 닿기를 기다린다 — 추첨 뒤에는 빠진 응모를 되돌릴 수 없다.</li>
 * </ul>
 * 끝내는 길은 넷이다. 일시 실패 · 기다림은 지우지 않고 늦춰 다시 받는다(큐의 최대 수신 횟수를 넘으면 SQS 가 DLQ 로 옮긴다).
 * 다시 보내도 같은 거절은 DLQ 에 바로 넣고 지운다. 지금 부를 수 없으면(차단기 · 거절 이어짐 · 자리 없음 · 내려가는 중) 받은 횟수를
 * 쓰지 않게 새 메시지로 돌려보낸다(설정 오류는 정한 시간이 지나면 DLQ). 모르는 종류는 지우지 않는다 — 그 종류를 아는 worker 가 받거나
 * 끝내 DLQ 로 간다.
 */
final class DrawHandler implements BatchMessageHandler {

    static final String METRIC = "worker.draws";
    /** {@link #METRIC} 의 outcome 값. 경보가 "값 없음"과 0 을 가리지 않아도 되게 처음부터 0 으로 내보낸다. */
    static final List<String> OUTCOMES =
            List.of("succeeded", "retried", "waiting", "dead-lettered", "held", "returned", "unknown-type");
    private static final String UNKNOWN_EVENT = "UNKNOWN";
    private static final String INVALID_MESSAGE = "INVALID_MESSAGE";
    private static final String INVALID_DRAW_RESULT = "INVALID_DRAW_RESULT";
    private static final String REJECTED = "REJECTED";
    private static final String MISCONFIGURED = "MISCONFIGURED";
    private static final String ENTRY_AGGREGATE = "DRAW_ENTRY";
    private static final int LOGGED_BODY = 500;
    private static final Logger log = LoggerFactory.getLogger(DrawHandler.class);

    private final MockGateway mock;
    private final InRoundRetry inRound;
    private final ConcurrencyLimiter limiter;
    private final MockCircuit circuit;
    private final RejectGuard rejectGuard;
    private final ResultPublisher publisher;
    private final DelayedResend resend;
    private final DeadLetterSender deadLetterSender;
    private final WorkerProperties.Draw delays;
    private final Duration permitWait;
    private final Duration requeueDelay;
    private final Duration misconfiguredDelay;
    private final Duration misconfiguredGiveUp;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ShutdownSignal shutdown;

    DrawHandler(MockGateway mock, InRoundRetry inRound, ConcurrencyLimiter limiter, MockCircuit circuit,
                RejectGuard rejectGuard, ResultPublisher publisher, DelayedResend resend,
                DeadLetterSender deadLetterSender, WorkerProperties properties, JsonMapper json, MeterRegistry meters,
                Clock clock, ShutdownSignal shutdown) {
        this.mock = mock;
        this.inRound = inRound;
        this.limiter = limiter;
        this.circuit = circuit;
        this.rejectGuard = rejectGuard;
        this.publisher = publisher;
        this.resend = resend;
        this.deadLetterSender = deadLetterSender;
        this.delays = properties.draw();
        this.permitWait = properties.concurrency().permitWait();
        this.requeueDelay = properties.consumer().requeueDelay();
        this.misconfiguredDelay = properties.retry().backoff().getFirst();
        this.misconfiguredGiveUp = properties.rejectGuard().giveUpAfter();
        this.json = json;
        this.meters = meters;
        this.clock = clock;
        this.shutdown = shutdown;
        for (DrawEventType type : DrawEventType.values()) {
            OUTCOMES.forEach(outcome -> meters.counter(METRIC, "eventType", type.name(), "outcome", outcome));
        }
        meters.timer(JobHandler.MISCONFIGURED_METRIC, "jobType", JobType.DRAW.name());
        // 종류를 모르는 메시지가 끝나는 길: 모르는 종류 · 읽을 수 없는 본문(DLQ) · 내려가는 중 돌려보냄
        List.of("unknown-type", "dead-lettered", "returned")
                .forEach(outcome -> meters.counter(METRIC, "eventType", UNKNOWN_EVENT, "outcome", outcome));
    }

    @Override
    public MessageOutcome handle(Message message) {
        if (shutdown.stopping()) {
            return giveBack(message);
        }
        Optional<EventEnvelope> envelope = envelopeOf(message);
        DrawEventType type = envelope.map(e -> typeOf(e.eventType())).orElse(null);
        if (envelope.isPresent() && type == null) {
            log.error("모르는 종류의 드로우 메시지다 — 지우지 않고 다시 받는다(끝내 처리하지 못하면 DLQ) eventType={} messageId={}",
                    envelope.get().eventType(), message.messageId());
            count(UNKNOWN_EVENT, "unknown-type");
            return MessageOutcome.keep();
        }
        Optional<Call> call = envelope.flatMap(e -> callOf(type, e));
        if (call.isEmpty()) {
            log.error("드로우 메시지의 내용이 계약과 달라 부르지 않고 DLQ 에 넣는다 — order 와 계약 확인 필요 eventType={} messageId={}",
                    type, message.messageId());
            return deadLetter(message, type == null ? UNKNOWN_EVENT : type.name(), INVALID_MESSAGE);
        }
        if (circuit.isOpen()) {
            return hold(message, call.get(), longer(circuit.openRemaining(), requeueDelay));
        }
        if (rejectGuard.blocked()) {
            return hold(message, call.get(), longer(rejectGuard.remaining(), requeueDelay));
        }
        Step step;
        try (Permit permit = limiter.tryAcquire(JobType.DRAW, permitWait).orElse(null)) {
            if (permit == null) {
                return hold(message, call.get(), requeueDelay);
            }
            // 자리를 기다리는 사이에 내려가기 시작했을 수 있다
            if (shutdown.stopping()) {
                return giveBack(message);
            }
            step = switch (call.get()) {
                case Entry entry -> enter(entry);
                case Campaign campaign -> draw(campaign);
            };
        }
        return settle(message, call.get(), step);
    }

    private Step enter(Entry entry) {
        String key = entry.entryId().toString();
        JsonNode body = json.valueToTree(new EntryBody(key, entry.paid().drawId().toString(),
                entry.paid().customerId().toString()));
        return new Step(inRound.from(mock.enterDraw(key, body), () -> mock.enterDraw(key, body)), null);
    }

    /** 받은 응모 수를 먼저 본다. 같을 때만 추첨한다 — 추첨 요청에도 기대 수를 실어 Mock 이 다시 센다. */
    private Step draw(Campaign campaign) {
        String drawId = campaign.drawId().toString();
        MockResult status = inRound.from(mock.drawStatus(drawId), () -> mock.drawStatus(drawId));
        long received;
        if (status.verdict() == Verdict.SUCCESS) {
            received = status.body().get("entryCount").asLong();
        } else if (status.verdict() == Verdict.ABSENT) {
            received = 0;
        } else {
            return new Step(status, null);
        }
        if (received != campaign.requested().paidEntries()) {
            return new Step(status, received);
        }
        JsonNode body = json.valueToTree(new DrawBody(campaign.requested().winnerCount(),
                campaign.requested().paidEntries()));
        return new Step(inRound.from(mock.draw(drawId, body), () -> mock.draw(drawId, body)), received);
    }

    private MessageOutcome settle(Message message, Call call, Step step) {
        MockResult last = step.last();
        if (call instanceof Campaign campaign && step.received() != null
                && step.received().longValue() != campaign.requested().paidEntries()) {
            return waitFor(message, campaign, "received=" + step.received());
        }
        return switch (last.verdict()) {
            case NOT_CALLED -> hold(message, call, last.skipped() == Skipped.CIRCUIT_OPEN
                    ? longer(circuit.openRemaining(), requeueDelay) : requeueDelay);
            case MISCONFIGURED -> misconfigured(message, call);
            case SUCCESS -> switch (call) {
                case Entry _ -> done(call, "succeeded");
                case Campaign campaign -> publish(message, campaign, last);
            };
            case PENDING -> waitFor(message, (Campaign) call, "errorCode=" + last.errorCode());
            case TRANSIENT, UNKNOWN -> retry(message, call, last);
            case REJECTED -> {
                log.error("Mock 이 드로우 요청을 거절해 DLQ 에 넣는다 — 사람 확인 필요 eventType={} id={} httpStatus={} errorCode={} "
                                + "message={}", call.type(), call.id(), last.httpStatus(), last.errorCode(),
                        last.errorMessage());
                yield deadLetter(message, call.type().name(), last.errorCode() == null ? REJECTED : last.errorCode());
            }
            case ABSENT -> throw new IllegalStateException("회차 조회의 응모 없음은 응모 수 0 으로 읽는다: drawId=" + call.id());
        };
    }

    /** 당첨 목록이 계약대로면(당첨 인원만큼 · 겹치지 않음 · 응모 id) order 에 보낸다. 보내지 못하면 던져 원본을 남긴다(다시 받으면 Mock 이 재생). */
    private MessageOutcome publish(Message message, Campaign campaign, MockResult last) {
        Optional<List<UUID>> winners = winnersOf(last.body(), campaign.requested().winnerCount());
        if (winners.isEmpty()) {
            log.error("Mock 의 추첨 결과가 계약과 달라 order 에 보내지 않고 DLQ 에 넣는다 — Mock 확인 필요 drawId={} winnerCount={} body={}",
                    campaign.drawId(), campaign.requested().winnerCount(), abbreviate(last.body()));
            return deadLetter(message, campaign.type().name(), INVALID_DRAW_RESULT);
        }
        publisher.publishDrawResult(campaign.drawId(), new DrawResultReady(winners.get()));
        log.info("추첨 결과를 order 에 보냈다 drawId={} paidEntries={} winners={}", campaign.drawId(),
                campaign.requested().paidEntries(), winners.get().size());
        return done(campaign, "succeeded");
    }

    /** 받은 응모 수가 다르다. 늦은 응모가 아직 재시도 중일 수 있어 거절로 보지 않고, 받은 횟수에 따라 점점 길게 늦춰 다시 받는다. */
    private MessageOutcome waitFor(Message message, Campaign campaign, String detail) {
        int receiveCount = receiveCount(message);
        Duration delay = delays.waitDelay(receiveCount);
        log.warn("Mock 이 받은 응모 수가 결제 완료 수와 달라 추첨을 미룬다 drawId={} paidEntries={} {} receiveCount={} delay={}",
                campaign.drawId(), campaign.requested().paidEntries(), detail, receiveCount, delay);
        count(campaign.type().name(), "waiting");
        return MessageOutcome.returnAfter(delay);
    }

    /** 같은 요청을 다시 보내면 된다(Mock 이 재생한다). 응답이 정한 지연이 더 길면 따르되 가장 긴 재시도 간격을 넘지 않는다. */
    private MessageOutcome retry(Message message, Call call, MockResult last) {
        int receiveCount = receiveCount(message);
        Duration delay = last.retryAfter() == null ? delays.retryDelay(receiveCount)
                : shorter(longer(last.retryAfter(), delays.retryDelay(receiveCount)), delays.maxRetryDelay());
        log.warn("드로우 요청이 일시 실패해 늦춰 다시 보낸다 eventType={} id={} verdict={} httpStatus={} errorCode={} "
                        + "receiveCount={} delay={}", call.type(), call.id(), last.verdict(), last.httpStatus(),
                last.errorCode(), receiveCount, delay);
        count(call.type().name(), "retried");
        return MessageOutcome.returnAfter(delay);
    }

    /**
     * 설정 오류(mock 이 아닌 곳의 응답)는 돌려보내고, 처음 본 때부터 정한 시간이 지나도 그대로면 DLQ 에 넣어 사람이 보게 한다.
     * 늦춰 다시 받는 길은 속성을 고칠 수 없어 시각이 남는다 — 같은 메시지가 설정 오류를 두 번 겪으면 일찍 DLQ 로 간다(되돌릴 수 있다).
     */
    private MessageOutcome misconfigured(Message message, Call call) {
        Instant now = clock.instant();
        Instant since = JobProgress.misconfiguredSince(message).orElse(now);
        if (!now.isBefore(since.plus(misconfiguredGiveUp))) {
            log.error("Mock 이 아닌 곳의 응답이 {} 넘게 이어져 드로우 메시지를 DLQ 에 넣는다 — 주소 · 경로 설정 확인 필요 eventType={} id={}",
                    misconfiguredGiveUp, call.type(), call.id());
            return deadLetter(message, call.type().name(), MISCONFIGURED);
        }
        meters.timer(JobHandler.MISCONFIGURED_METRIC, "jobType", JobType.DRAW.name())
                .record(Duration.between(since, now));
        return hold(message, call, longer(rejectGuard.remaining(), misconfiguredDelay),
                Map.of(JobProgress.MISCONFIGURED_SINCE, since.toString()));
    }

    /** 차례를 쓰지 않고 돌려보낸다. 받은 횟수를 쓰지 않게 가시성 시간 대신 새 메시지로 보낸다. */
    private MessageOutcome hold(Message message, Call call, Duration delay) {
        return hold(message, call, delay, Map.of());
    }

    /** @param attributes 덮어쓸 속성(값이 null 이면 뺀다) */
    private MessageOutcome hold(Message message, Call call, Duration delay, Map<String, String> attributes) {
        log.debug("지금 부를 수 없어 드로우 메시지를 돌려보낸다 eventType={} messageId={} delay={}", call.type(),
                message.messageId(), delay);
        resend.resend(message, attributes, delay);
        return done(call, "held");
    }

    /** 내려가는 중이다. 아직 부르지 않았으므로 다른 worker 가 곧바로 받게 지연 없이 다시 보낸다(받은 횟수를 쓰지 않는다). */
    private MessageOutcome giveBack(Message message) {
        resend.resend(message, Map.of(), Duration.ZERO);
        count(Optional.ofNullable(typeOf(attribute(message, "eventType"))).map(Enum::name).orElse(UNKNOWN_EVENT),
                "returned");
        return MessageOutcome.delete();
    }

    private MessageOutcome deadLetter(Message message, String eventType, String reason) {
        deadLetterSender.send(message, reason);
        count(eventType, "dead-lettered");
        return MessageOutcome.delete();
    }

    private MessageOutcome done(Call call, String outcome) {
        count(call.type().name(), outcome);
        return MessageOutcome.delete();
    }

    private void count(String eventType, String outcome) {
        meters.counter(METRIC, "eventType", eventType, "outcome", outcome).increment();
    }

    private Optional<EventEnvelope> envelopeOf(Message message) {
        try {
            return Optional.ofNullable(json.readValue(message.body(), EventEnvelope.class));
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("드로우 메시지 본문을 읽지 못했다 messageId={}", message.messageId(), e);
            return Optional.empty();
        }
    }

    /** 계약대로 부를 수 있는 메시지인가. 대상 id · 봉투 종류 · payload 칸이 맞지 않으면 비어 있다. */
    private Optional<Call> callOf(DrawEventType type, EventEnvelope envelope) {
        if (envelope.aggregateId() == null || envelope.payload() == null) {
            return Optional.empty();
        }
        try {
            return switch (type) {
                case DRAW_ENTRY_PAID -> {
                    DrawEntryPaid paid = json.treeToValue(envelope.payload(), DrawEntryPaid.class);
                    yield ENTRY_AGGREGATE.equals(envelope.aggregateType()) && paid.drawId() != null
                            && paid.customerId() != null
                            ? Optional.of(new Entry(envelope.aggregateId(), paid)) : Optional.empty();
                }
                case DRAW_REQUESTED -> {
                    DrawRequested requested = json.treeToValue(envelope.payload(), DrawRequested.class);
                    yield DrawResultReady.AGGREGATE_TYPE.equals(envelope.aggregateType())
                            && requested.paidEntries() != null && requested.winnerCount() != null
                            && requested.winnerCount() >= 1 && requested.paidEntries() > requested.winnerCount()
                            ? Optional.of(new Campaign(envelope.aggregateId(), requested)) : Optional.empty();
                }
            };
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<List<UUID>> winnersOf(JsonNode body, int winnerCount) {
        JsonNode ids = body == null ? null : body.get("winnerEntryIds");
        if (ids == null || !ids.isArray() || ids.size() != winnerCount) {
            return Optional.empty();
        }
        List<UUID> winners = new ArrayList<>(ids.size());
        for (JsonNode id : ids) {
            try {
                winners.add(UUID.fromString(id.isString() ? id.stringValue() : ""));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return new HashSet<>(winners).size() == winners.size() ? Optional.of(winners) : Optional.empty();
    }

    private static DrawEventType typeOf(String eventType) {
        if (eventType == null) {
            return null;
        }
        try {
            return DrawEventType.valueOf(eventType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 이 메시지를 받은 횟수(1부터). 읽을 수 없으면 1 로 본다 — 지연이 짧아질 뿐 흐름은 이어진다.
     * common:sqs QueuePoller.receiveCount 와 같은 규칙이다. 그쪽은 소비기 내부용(protected)이라 처리 함수에서는 부를 수 없다.
     */
    private static int receiveCount(Message message) {
        try {
            long count = Long.parseLong(
                    message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"));
            return (int) Math.clamp(count, 1, Integer.MAX_VALUE);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String attribute(Message message, String name) {
        MessageAttributeValue value = message.messageAttributes().get(name);
        return value == null ? null : value.stringValue();
    }

    private static Duration longer(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** 로그에 남길 본문. 엉뚱한 응답이 커도 앞부분만 남긴다. */
    private static String abbreviate(JsonNode body) {
        String text = String.valueOf(body);
        return text.length() <= LOGGED_BODY ? text : text.substring(0, LOGGED_BODY) + "…";
    }

    private static Duration shorter(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** 부를 메시지 하나. */
    private sealed interface Call permits Entry, Campaign {

        DrawEventType type();

        UUID id();
    }

    private record Entry(UUID entryId, DrawEntryPaid paid) implements Call {

        @Override
        public DrawEventType type() {
            return DrawEventType.DRAW_ENTRY_PAID;
        }

        @Override
        public UUID id() {
            return entryId;
        }
    }

    private record Campaign(UUID drawId, DrawRequested requested) implements Call {

        @Override
        public DrawEventType type() {
            return DrawEventType.DRAW_REQUESTED;
        }

        @Override
        public UUID id() {
            return drawId;
        }
    }

    /**
     * @param last     마지막 호출의 결과
     * @param received 추첨: Mock 이 받은 응모 수(조회가 실패했으면 null). 응모: null
     */
    private record Step(MockResult last, Long received) {
    }

    /** Mock 응모 본문(api.md 「럭키드로우」). customerId 는 소문자 표준 UUID 여야 한다 — UUID.toString 이 그렇다. */
    private record EntryBody(String entryId, String drawId, String customerId) {
    }

    private record DrawBody(int winnerCount, int expectedEntryCount) {
    }
}
