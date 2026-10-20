package com.grandis.nova.worker.sync;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.BatchMessageHandler;
import com.grandis.nova.common.sqs.DelayedResend;
import com.grandis.nova.common.sqs.MessageOutcome;
import com.grandis.nova.worker.config.WorkerProperties;
import com.grandis.nova.worker.event.Attempt;
import com.grandis.nova.worker.event.ExternalJobAttempted;
import com.grandis.nova.worker.event.ExternalJobFailed;
import com.grandis.nova.worker.event.ExternalJobSucceeded;
import com.grandis.nova.worker.event.ResultEventType;
import com.grandis.nova.worker.job.AttemptResult;
import com.grandis.nova.worker.job.JobProgress;
import com.grandis.nova.worker.job.JobReady;
import com.grandis.nova.worker.job.JobType;
import com.grandis.nova.worker.mock.ConcurrencyLimiter;
import com.grandis.nova.worker.mock.ConcurrencyLimiter.Permit;
import com.grandis.nova.worker.mock.MockCircuit;
import com.grandis.nova.worker.mock.MockResult;
import com.grandis.nova.worker.mock.MockResult.Skipped;
import com.grandis.nova.worker.mock.RejectGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 작업 메시지 하나를 한 차례 처리한다. 상태는 메시지 속성에만 있다 — 결과를 preorder 에 알리고, 다시 해야 하면 속성을 고쳐
 * 지연을 두고 같은 큐에 다시 보낸 뒤 원본을 지운다. 알리거나 다시 보내지 못하면 던져서 원본을 남긴다(가시성 시간 뒤 같은 차례를 다시 한다).
 * 지금 부를 수 없으면(차단기 · 거절 이어짐 · 자리 없음) 차례를 쓰지 않고 속성 그대로 돌려보낸다.
 * 내려가는 중에 받은 메시지는 부르지 않고 속성 그대로 곧바로 다시 보낸다.
 */
final class JobHandler implements BatchMessageHandler {

    static final String JOBS_METRIC = "worker.jobs";
    /** 한 세대를 다 쓰고 다음 세대로 넘어간 횟수. 포기하지 않는 CANCEL 은 이 값이 "계속 실패 중"의 신호다. */
    static final String GENERATIONS_METRIC = "worker.jobs.generations";
    static final String PAYLOAD_INVALID = "PAYLOAD_INVALID";
    static final String GENERATION_EXHAUSTED = "GENERATION_EXHAUSTED";
    private static final String REJECTED = "REJECTED";
    /** {@link #JOBS_METRIC} 의 outcome 값. 경보가 "값 없음"과 0 을 가리지 않아도 되게 처음부터 0 으로 내보낸다. */
    static final List<String> OUTCOMES =
            List.of("succeeded", "failed", "retried", "held", "returned", "invalid", "unreadable");
    private static final Logger log = LoggerFactory.getLogger(JobHandler.class);

    private final JobType type;
    private final Turn turn;
    private final RetrySchedule schedule;
    private final ConcurrencyLimiter limiter;
    private final MockCircuit circuit;
    private final RejectGuard rejectGuard;
    private final ResultPublisher publisher;
    private final DelayedResend resend;
    private final Duration permitWait;
    private final Duration requeueDelay;
    private final Duration misconfiguredDelay;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ShutdownSignal shutdown;

    JobHandler(JobType type, Turn turn, RetrySchedule schedule, ConcurrencyLimiter limiter, MockCircuit circuit,
               RejectGuard rejectGuard, ResultPublisher publisher, DelayedResend resend, WorkerProperties properties,
               JsonMapper json, MeterRegistry meters, Clock clock, ShutdownSignal shutdown) {
        this.type = type;
        this.turn = turn;
        this.schedule = schedule;
        this.limiter = limiter;
        this.circuit = circuit;
        this.rejectGuard = rejectGuard;
        this.publisher = publisher;
        this.resend = resend;
        this.permitWait = properties.concurrency().permitWait();
        this.requeueDelay = properties.consumer().requeueDelay();
        this.misconfiguredDelay = properties.retry().backoff().getFirst();
        this.json = json;
        this.meters = meters;
        this.clock = clock;
        this.shutdown = shutdown;
        OUTCOMES.forEach(outcome -> meters.counter(JOBS_METRIC, "jobType", type.name(), "outcome", outcome));
        meters.counter(GENERATIONS_METRIC, "jobType", type.name());
    }

    @Override
    public MessageOutcome handle(Message message) {
        if (shutdown.stopping()) {
            return giveBack(message);
        }
        JobProgress progress = JobProgress.of(message);
        Optional<JsonNode> payload = payloadOf(message);
        if (payload.isEmpty()) {
            return done("unreadable");
        }
        UUID syncJobId = UUID.fromString(payload.get().get("syncJobId").asString());
        Optional<JobReady> job = jobOf(payload.get());
        if (job.isEmpty()) {
            Instant now = clock.instant();
            log.error("작업 메시지의 내용이 깨져 외부를 부르지 않고 포기한다 syncJobId={} messageId={}", syncJobId, message.messageId());
            publisher.publish(ResultEventType.EXTERNAL_JOB_FAILED, syncJobId, new ExternalJobFailed(syncJobId,
                    text(payload.get(), "preorderId"), type, PAYLOAD_INVALID, new Attempt(progress.run(),
                    progress.generation(), progress.attempt(), progress.actor(), AttemptResult.REJECTED, null,
                    PAYLOAD_INVALID, "작업 메시지에 보낼 내용이 없거나 종류가 다르다", now, now)));
            return done("invalid");
        }
        if (circuit.isOpen()) {
            return hold(message, longer(schedule.past(circuit.openRemaining()), requeueDelay));
        }
        if (rejectGuard.blocked()) {
            return hold(message, longer(schedule.past(rejectGuard.remaining()), requeueDelay));
        }
        Turn.Outcome outcome;
        try (Permit permit = limiter.tryAcquire(type, permitWait).orElse(null)) {
            if (permit == null) {
                return hold(message, requeueDelay);
            }
            // 자리를 기다리는 사이에 내려가기 시작했을 수 있다
            if (shutdown.stopping()) {
                return giveBack(message);
            }
            outcome = turn.run(job.get(), progress);
        }
        return settle(message, job.get(), progress, outcome);
    }

    private MessageOutcome settle(Message message, JobReady job, JobProgress progress, Turn.Outcome outcome) {
        MockResult last = outcome.last();
        return switch (last.verdict()) {
            case NOT_CALLED -> hold(message, last.skipped() == Skipped.CIRCUIT_OPEN
                    ? longer(schedule.past(circuit.openRemaining()), requeueDelay) : requeueDelay);
            case MISCONFIGURED -> hold(message, longer(schedule.past(rejectGuard.remaining()), misconfiguredDelay));
            case SUCCESS -> {
                publisher.publish(ResultEventType.EXTERNAL_JOB_SUCCEEDED, job.syncJobId(), new ExternalJobSucceeded(
                        job.syncJobId(), job.preorderId(), type, last.externalNumber(), attemptOf(progress, outcome)));
                yield done("succeeded");
            }
            case REJECTED -> fail(job, progress, outcome, last.errorCode() == null ? REJECTED : last.errorCode());
            case TRANSIENT, UNKNOWN -> retry(message, job, progress, outcome);
            // 조회의 "등록 없음"만으로는 차례를 끝낼 수 없고, 기다림은 드로우에만 나온다
            case ABSENT, PENDING -> throw new IllegalStateException(
                    "예약 작업의 차례를 끝낼 수 없는 판정이다: verdict=" + last.verdict() + " syncJobId=" + job.syncJobId());
        };
    }

    private MessageOutcome retry(Message message, JobReady job, JobProgress progress, Turn.Outcome outcome) {
        Optional<RetrySchedule.Next> next =
                schedule.next(type, progress, outcome.result(), outcome.last().retryAfter());
        if (next.isEmpty()) {
            return fail(job, progress, outcome, GENERATION_EXHAUSTED);
        }
        publisher.publish(ResultEventType.EXTERNAL_JOB_ATTEMPTED, job.syncJobId(), new ExternalJobAttempted(
                job.syncJobId(), job.preorderId(), type, attemptOf(progress, outcome)));
        resend.resend(message, next.get().progress().toAttributes(), next.get().delay());
        if (next.get().progress().generation() > progress.generation()) {
            log.warn("한 세대를 다 쓰고도 실패해 다음 세대로 넘긴다 jobType={} syncJobId={} generation={} delay={}", type,
                    job.syncJobId(), next.get().progress().generation(), next.get().delay());
            meters.counter(GENERATIONS_METRIC, "jobType", type.name()).increment();
        }
        return done("retried");
    }

    private MessageOutcome fail(JobReady job, JobProgress progress, Turn.Outcome outcome, String reason) {
        if (type == JobType.CANCEL) {
            // 외부에 등록이 남는다 — preorder 상태로는 드러나지 않으므로 사람이 봐야 한다
            log.error("외부 취소를 포기한다 — 외부에 등록이 남았을 수 있다 syncJobId={} preorderId={} reason={} "
                    + "generation={} attempt={}", job.syncJobId(), job.preorderId(), reason, progress.generation(),
                    progress.attempt());
        } else {
            log.warn("외부 작업을 포기한다 jobType={} syncJobId={} reason={} generation={} attempt={}", type,
                    job.syncJobId(), reason, progress.generation(), progress.attempt());
        }
        publisher.publish(ResultEventType.EXTERNAL_JOB_FAILED, job.syncJobId(), new ExternalJobFailed(
                job.syncJobId(), job.preorderId(), type, reason, attemptOf(progress, outcome)));
        return done("failed");
    }

    /** 차례를 쓰지 않고 속성 그대로 돌려보낸다. 받은 횟수를 쓰지 않게 가시성 시간 대신 새 메시지로 보낸다. */
    private MessageOutcome hold(Message message, Duration delay) {
        log.debug("지금 부를 수 없어 차례를 쓰지 않고 돌려보낸다 jobType={} messageId={} delay={}", type, message.messageId(),
                delay);
        resend.resend(message, Map.of(), delay);
        return done("held");
    }

    /** 내려가는 중이다. 아직 부르지 않았으므로 다른 worker 가 곧바로 받게 지연 없이 다시 보낸다(받은 횟수를 쓰지 않는다). */
    private MessageOutcome giveBack(Message message) {
        resend.resend(message, Map.of(), Duration.ZERO);
        return done("returned");
    }

    private MessageOutcome done(String outcome) {
        meters.counter(JOBS_METRIC, "jobType", type.name(), "outcome", outcome).increment();
        return MessageOutcome.delete();
    }

    private static Attempt attemptOf(JobProgress progress, Turn.Outcome outcome) {
        MockResult last = outcome.last();
        return new Attempt(progress.run(), progress.generation(), progress.attempt(), progress.actor(),
                outcome.result(), last.httpStatus(), last.errorCode(), last.errorMessage(), outcome.startedAt(),
                last.finishedAt());
    }

    /**
     * 봉투에서 payload 를 꺼낸다. 작업을 가리킬 수 없으면(봉투가 깨짐 · syncJobId 없음) 비어 있다 — 누구의 작업인지 몰라
     * 결과를 알릴 수도 없으므로 경보만 남기고 지운다.
     */
    private Optional<JsonNode> payloadOf(Message message) {
        try {
            JsonNode payload = json.readValue(message.body(), EventEnvelope.class).payload();
            String syncJobId = text(payload, "syncJobId");
            if (syncJobId != null) {
                UUID.fromString(syncJobId);
                return Optional.of(payload);
            }
            log.error("작업을 가리키지 않는 메시지를 버린다 — 보낸 쪽의 계약 확인 필요 jobType={} messageId={}", type,
                    message.messageId());
        } catch (JacksonException | IllegalArgumentException e) {
            log.error("읽을 수 없는 작업 메시지를 버린다 — 보낸 쪽의 계약 확인 필요 jobType={} messageId={}", type,
                    message.messageId(), e);
        }
        return Optional.empty();
    }

    /** 외부에 보낼 수 있는 작업인가. 예약 id(멱등 키) · 보낼 내용이 없거나 이 큐의 종류가 아니면 비어 있다. */
    private Optional<JobReady> jobOf(JsonNode payload) {
        try {
            JobReady job = json.treeToValue(payload, JobReady.class);
            boolean sendable = job.jobType() == type && job.preorderId() != null && !job.preorderId().isBlank()
                    && job.request() != null && job.request().isObject();
            return sendable ? Optional.of(job) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode payload, String name) {
        JsonNode value = payload == null ? null : payload.get(name);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static Duration longer(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
