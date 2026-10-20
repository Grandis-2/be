package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.mock.MockResult.Skipped;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 외부 mock 을 한 번 부르고 판정해 돌려준다. 부르기 전에 차단기 · 초당 상한을 보고, 부른 뒤 결과를 차단기 · 자리 조절 · 연쇄 거절
 * 차단 · 지표에 알린다. 다시 보내기와 자리 잡기({@link ConcurrencyLimiter})는 부르는 쪽이 한다 — 자리는 차례마다 하나다.
 */
public class MockGateway {

    private static final Logger log = LoggerFactory.getLogger(MockGateway.class);
    static final String CALLS_METRIC = "worker.mock.calls";
    static final String LATENCY_METRIC = "worker.mock.latency";
    static final String SKIPPED_METRIC = "worker.mock.skipped";

    private final MockClient client;
    private final MockCircuit circuit;
    private final RateLimiter rateLimiter;
    private final ConcurrencyLimiter limiter;
    private final RejectGuard rejectGuard;
    private final MeterRegistry meters;
    private final Clock clock;

    MockGateway(MockClient client, MockCircuit circuit, RateLimiter rateLimiter, ConcurrencyLimiter limiter,
                RejectGuard rejectGuard, MeterRegistry meters, Clock clock) {
        this.client = client;
        this.circuit = circuit;
        this.rateLimiter = rateLimiter;
        this.limiter = limiter;
        this.rejectGuard = rejectGuard;
        this.meters = meters;
        this.clock = clock;
    }

    /** @param key 멱등 키. 다시 보낼 때도 같은 키 · 같은 본문을 쓴다 */
    public MockResult register(String key, JsonNode request) {
        return call("register", () -> client.register(key, request), MockJudge::register);
    }

    /** 결과를 모르는 등록을 같은 키로 확인한다. */
    public MockResult lookup(String key) {
        return call("lookup", () -> client.lookup(key), MockJudge::lookup);
    }

    public MockResult cancel(JsonNode request) {
        return call("cancel", () -> client.cancel(request), MockJudge::cancel);
    }

    /** 드로우 응모. 다시 보낼 때도 같은 키(응모 id) · 같은 본문을 쓴다. */
    public MockResult enterDraw(String entryId, JsonNode request) {
        return call("draw-entry", () -> client.enterDraw(entryId, request), MockJudge::drawEntry);
    }

    /** 드로우 회차 — 받은 응모 수 · 추첨 결과. 응모가 아직 없으면 ABSENT. */
    public MockResult drawStatus(String drawId) {
        return call("draw-status", () -> client.drawStatus(drawId), MockJudge::drawStatus);
    }

    /** 추첨. 회차 id 가 멱등 키라 같은 요청을 다시 보내면 처음 결과가 온다. */
    public MockResult draw(String drawId, JsonNode request) {
        return call("draw", () -> client.draw(drawId, request), MockJudge::draw);
    }

    private MockResult call(String operation, Supplier<MockReply> send, Function<MockReply, Judgement> judge) {
        if (!circuit.tryAcquire()) {
            return skipped(operation, Skipped.CIRCUIT_OPEN);
        }
        if (!rateLimiter.acquirePermission()) {
            circuit.release();
            return skipped(operation, Skipped.RATE_LIMITED);
        }
        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        MockReply reply;
        Duration elapsed;
        Judgement judgement;
        // 차단기에 결과를 알리기 전에 빠져나가면 자리를 돌려준다. 반쯤 열린 동안 시험 자리가 새면 다시 닫히지 못한다
        try {
            reply = send.get();
            elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            judgement = judge.apply(reply);
        } catch (RuntimeException | Error e) {
            circuit.release();
            throw e;
        }
        Verdict verdict = judgement.verdict();
        circuit.record(verdict, elapsed);
        if (verdict != Verdict.MISCONFIGURED) {
            limiter.record(elapsed, verdict == Verdict.TRANSIENT || verdict == Verdict.UNKNOWN);
        }
        if (verdict == Verdict.MISCONFIGURED
                || verdict == Verdict.REJECTED && MockJudge.INVALID_REQUEST.equals(judgement.errorCode())) {
            rejectGuard.record(verdict == Verdict.MISCONFIGURED ? "MISCONFIGURED" : MockJudge.INVALID_REQUEST);
        }
        if (verdict == Verdict.MISCONFIGURED || verdict == Verdict.UNKNOWN && reply.responded() && reply.status() < 300) {
            log.warn("외부 mock 의 응답이 계약과 다르다 — 주소 · 경로 설정이나 계약 변경 확인 필요 operation={} status={} verdict={}",
                    operation, reply.status(), verdict);
        }
        meters.counter(CALLS_METRIC, "operation", operation, "verdict", verdict.name()).increment();
        meters.timer(LATENCY_METRIC, "operation", operation).record(elapsed);
        return MockResult.of(judgement, reply, startedAt, startedAt.plus(elapsed));
    }

    private MockResult skipped(String operation, Skipped reason) {
        meters.counter(SKIPPED_METRIC, "operation", operation, "reason", reason.name()).increment();
        return MockResult.notCalled(reason, clock.instant());
    }
}
