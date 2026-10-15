package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.redis.ControlStore;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 일정을 하나도 모르면 전체 재발행을 요청한다. 연달아 비어 있으면(정말 회차가 없을 때) 간격을 5분에서 1시간까지 늘리고,
 * 일정을 알게 되면 처음 간격으로 되돌린다. 일정을 아는 동안에도 주기마다 한 번 요청한다 — 메시지 하나를 놓치면 다른 일정이
 * 있는 한 메워지지 않아서다. 표식은 Redis 라 리더가 바뀌어도 이어진다. 배분 회차와 따로 돈다.
 */
@Component
class ScheduleResync {

    static final Duration FIRST_QUIET = Duration.ofMinutes(5);
    static final Duration MAX_QUIET = Duration.ofHours(1);
    /** 선점한 노드가 보내다 죽어도 이 뒤에는 다른 노드가 다시 요청한다. SQS 호출 시한보다 넉넉하다. */
    static final Duration CLAIM_TTL = Duration.ofMinutes(1);
    /** 보내지 못한 뒤 이 노드가 쉬는 기간. 권한 · 큐 없음처럼 오래 가는 실패에 매초 요청하지 않는다. */
    static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    static final Duration MAX_RETRY = Duration.ofMinutes(1);
    static final String EMPTY_REASON = "SCHEDULE_EMPTY";
    static final String PERIODIC_REASON = "PERIODIC";
    private static final Logger log = LoggerFactory.getLogger(ScheduleResync.class);

    private final ControlStore store;
    private final ScheduleResyncRequester requester;
    private final ControlMetrics metrics;
    private final ScheduleResyncProperties properties;
    private final Clock clock;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    /** 이 노드가 모르는 동안 센 횟수가 Redis 에 남아 있을 수 있다. 일정을 알게 되면 한 번 지운다. */
    private final AtomicBoolean attemptsOutstanding = new AtomicBoolean(true);
    private final AtomicInteger sendFailures = new AtomicInteger();
    private volatile Instant retryAt = Instant.MIN;
    /** 보낸 뒤 Redis 표식을 세우지 못해도 이 노드는 조용한 기간을 지킨다 — 선점 시한마다 되풀이해 보내지 않게. */
    private volatile Instant quietUntil = Instant.MIN;
    /** 주기 표식이 살아 있는 동안은 Redis 에 묻지 않는다. */
    private volatile Instant periodicProbeAt = Instant.MIN;

    ScheduleResync(ControlStore store, ScheduleResyncRequester requester, ControlMetrics metrics,
                   ScheduleResyncProperties properties, Clock clock) {
        if (requester.maxRequestTime().compareTo(CLAIM_TTL) >= 0) {
            throw new IllegalArgumentException("재발행 요청 최대 시간(" + requester.maxRequestTime() + ")이 선점 시한("
                    + CLAIM_TTL + ")보다 짧아야 한다 — nova.sqs.api-call-timeout 을 줄인다");
        }
        this.store = store;
        this.requester = requester;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** n 번째 연속 요청 뒤 기다릴 기간. 두 배씩, 상한까지. */
    static Duration quietAfter(long attempt) {
        return doubled(FIRST_QUIET, MAX_QUIET, attempt);
    }

    /** n 번 연달아 보내지 못한 뒤 쉴 기간. 두 배씩, 상한까지. */
    static Duration retryAfter(long failures) {
        return doubled(FIRST_RETRY, MAX_RETRY, failures);
    }

    private static Duration doubled(Duration first, Duration max, long count) {
        Duration doubled = first.multipliedBy(1L << Math.clamp(count - 1, 0, 10));
        return doubled.compareTo(max) > 0 ? max : doubled;
    }

    void requestIfQuiet() {
        attemptsOutstanding.set(true);
        if (clock.instant().isBefore(quietUntil)) {
            return;
        }
        // 방금 전체를 요청했으니 주기 요청도 그만큼 미룬다
        Mono<Long> sent = store.nextResyncAttempt()
                .doOnNext(attempt -> quietUntil = clock.instant().plus(quietAfter(attempt)))
                .flatMap(attempt -> store.markResyncRequested(RedisKeys.RESYNC_REQUESTED, quietAfter(attempt))
                        .then(markPeriodic())
                        .thenReturn(attempt));
        send(RedisKeys.RESYNC_REQUESTED, EMPTY_REASON, sent);
    }

    /** 일정을 아는 동안의 주기 요청. 받는 쪽은 번호가 큰 일정만 반영하므로 같은 일정이 다시 와도 바뀌지 않는다. */
    void requestIfDue() {
        if (!properties.periodicEnabled() || clock.instant().isBefore(periodicProbeAt)) {
            return;
        }
        send(RedisKeys.RESYNC_PERIODIC, PERIODIC_REASON, markPeriodic().thenReturn(0L));
    }

    /** 일정을 알게 됐다. 연속 횟수를 지워 다음에 비면 처음 간격부터 요청한다. */
    void known() {
        if (attemptsOutstanding.compareAndSet(true, false)) {
            store.clearResyncAttempts().subscribe(cleared -> {
            }, e -> attemptsOutstanding.set(true));
        }
    }

    /** @param afterSent 보낸 뒤 표식을 세우고 연속 횟수(주기 요청은 0)를 돌려준다 */
    private void send(String marker, String reason, Mono<Long> afterSent) {
        if (clock.instant().isBefore(retryAt) || !inFlight.compareAndSet(false, true)) {
            return;
        }
        String token = UUID.randomUUID().toString();
        store.claimResync(marker, token, CLAIM_TTL)
                .doOnNext(claimed -> {
                    // 표식이 있으면 선점 시한 안에는 사라지지 않는다. 주기 표식은 그동안 다시 묻지 않는다
                    if (!claimed && RedisKeys.RESYNC_PERIODIC.equals(marker)) {
                        periodicProbeAt = clock.instant().plus(CLAIM_TTL);
                    }
                })
                .flatMap(claimed -> !claimed ? Mono.<Long>empty() : requester.request(reason)
                        .thenReturn(true)
                        // 못 보냈으면 내 선점만 풀어 다시 요청한다. 보낸 뒤의 실패는 선점 시한이 대신 막는다
                        .onErrorResume(e -> store.releaseResyncClaim(marker, token)
                                .onErrorResume(ignored -> Mono.empty())
                                .then(Mono.fromRunnable(() -> sendFailed(e)))
                                .thenReturn(false))
                        .flatMap(delivered -> !delivered ? Mono.<Long>empty()
                                : Mono.fromRunnable(() -> onSent(marker)).then(afterSent)))
                .doOnNext(attempt -> {
                    metrics.resyncRequested(reason);
                    // 정말 회차가 없으면 계속 비어 있다 — 처음 한 번만 INFO 로 남긴다
                    if (attempt == 1) {
                        log.info("회차 일정을 하나도 몰라 전체 재발행을 요청했다");
                    } else if (attempt > 1) {
                        log.debug("회차 일정이 여전히 비어 재발행을 다시 요청했다(연속 {}번째)", attempt);
                    } else {
                        log.debug("회차 일정 주기 재발행을 요청했다");
                    }
                })
                .doFinally(signal -> inFlight.set(false))
                .subscribe(attempt -> {
                }, e -> log.warn("회차 일정 재발행 표식을 다루지 못했다 — 다음 회차에 다시 한다: {}", e.toString()));
    }

    private Mono<Boolean> markPeriodic() {
        if (!properties.periodicEnabled()) {
            return Mono.just(false);
        }
        return store.markResyncRequested(RedisKeys.RESYNC_PERIODIC, properties.periodic())
                .doOnNext(marked -> periodicProbeAt = clock.instant().plus(properties.periodic()));
    }

    /** 오래 가는 실패가 로그를 덮지 않게, 시작될 때 한 번만 WARN 으로 남긴다. */
    private void sendFailed(Throwable e) {
        int failures = sendFailures.incrementAndGet();
        Duration rest = retryAfter(failures);
        retryAt = clock.instant().plus(rest);
        if (failures == 1) {
            log.warn("회차 일정 재발행을 요청하지 못했다 — {} 뒤부터 간격을 늘려 다시 한다: {}", rest, e.toString());
        } else {
            log.debug("회차 일정 재발행을 여전히 요청하지 못한다(연속 {}번째, {} 뒤 다시): {}", failures, rest, e.toString());
        }
    }

    /** 표식보다 먼저 이 노드의 기억부터 미룬다. 횟수를 아직 몰라 가장 짧은 기간으로 잡고, 알게 되면 늘린다. */
    private void onSent(String marker) {
        if (sendFailures.getAndSet(0) > 0) {
            log.info("회차 일정 재발행 요청이 다시 된다");
        }
        Instant now = clock.instant();
        if (RedisKeys.RESYNC_REQUESTED.equals(marker)) {
            quietUntil = now.plus(FIRST_QUIET);
        }
        if (properties.periodicEnabled()) {
            periodicProbeAt = now.plus(properties.periodic());
        }
    }
}
