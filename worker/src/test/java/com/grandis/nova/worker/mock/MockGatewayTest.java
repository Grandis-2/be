package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.job.AttemptResult;
import com.grandis.nova.worker.job.JobType;
import com.grandis.nova.worker.mock.MockResult.Skipped;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 가짜 mock 으로 호출 한 번의 흐름을 본다: 차단기 · 초당 상한 → 호출 → 판정 → 차단기 · 자리 조절 · 연쇄 거절 · 지표. */
class MockGatewayTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNode REQUEST = JSON.readTree("{\"ourReservationId\":\"K-1\"}");
    static final String UNAVAILABLE = "{\"errorCode\":\"UPSTREAM_UNAVAILABLE\",\"errorMessage\":\"down\"}";

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final Clock clock = Clock.systemUTC();
    FakeMock mock;
    CloseableHttpClient http;
    ConcurrencyLimiter limiter;
    MockCircuit circuit;
    RejectGuard rejectGuard;
    MockGateway gateway;
    ReceiveGate gate;

    @BeforeEach
    void setUp() {
        mock = new FakeMock();
        build(RateLimiterConfig.custom().limitForPeriod(1000).limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(100)).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        http.close();
        mock.close();
    }

    @Test
    void 성공하면_번호와_걸린_시간을_돌려주고_지표에_센다() {
        mock.respond(FakeMock.json(201, "{\"externalNumber\":\"R-1\"}"));

        MockResult result = gateway.register("K-1", REQUEST);

        assertThat(result.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(result.attemptResult()).isEqualTo(AttemptResult.SUCCESS);
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.externalNumber()).isEqualTo("R-1");
        assertThat(result.finishedAt()).isAfterOrEqualTo(result.startedAt());
        assertThat(result.elapsed()).isPositive();
        assertThat(meters.counter(MockGateway.CALLS_METRIC, "operation", "register", "verdict", "SUCCESS").count())
                .isEqualTo(1);
        assertThat(meters.timer(MockGateway.LATENCY_METRIC, "operation", "register").count()).isEqualTo(1);
    }

    @Test
    void 조회에_등록이_없으면_등록_없음이고_시도로_세지_않는다() {
        mock.respond(FakeMock.json(404, "{\"errorCode\":\"NOT_FOUND\"}"));

        MockResult result = gateway.lookup("K-1");

        assertThat(result.verdict()).isEqualTo(Verdict.ABSENT);
        assertThat(result.attemptResult()).isNull();
    }

    @Test
    void 실패가_이어지면_차단기가_열려_호출하지_않고_받기도_멈추며_자리를_최소로_줄인다() {
        mock.respond(FakeMock.json(500, UNAVAILABLE));
        for (int i = 0; i < 4; i++) {
            assertThat(gateway.register("K-1", REQUEST).verdict()).isEqualTo(Verdict.TRANSIENT);
        }

        MockResult result = gateway.register("K-1", REQUEST);

        assertThat(result.verdict()).isEqualTo(Verdict.NOT_CALLED);
        assertThat(result.skipped()).isEqualTo(Skipped.CIRCUIT_OPEN);
        assertThat(result.httpStatus()).isNull();
        assertThat(mock.requests()).as("열린 뒤에는 보내지 않는다").hasSize(4);
        assertThat(gate.receivable(JobType.CANCEL, 0)).isZero();
        assertThat(limiter.limit()).isEqualTo(2);
        assertThat(meters.counter(MockGateway.SKIPPED_METRIC, "operation", "register", "reason", "CIRCUIT_OPEN")
                .count()).isEqualTo(1);
    }

    @Test
    void 초당_상한을_넘으면_호출하지_않고_차단기에도_세지_않는다() throws Exception {
        http.close();
        build(RateLimiterConfig.custom().limitForPeriod(1).limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofMillis(20)).build());
        mock.respond(FakeMock.json(201, "{\"externalNumber\":\"R-1\"}"));
        gateway.register("K-1", REQUEST);

        for (int i = 0; i < 10; i++) {
            MockResult result = gateway.register("K-1", REQUEST);
            assertThat(result.verdict()).isEqualTo(Verdict.NOT_CALLED);
            assertThat(result.skipped()).isEqualTo(Skipped.RATE_LIMITED);
        }

        assertThat(mock.requests()).hasSize(1);
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    void 잘못된_요청_거절이_이어지면_받기를_멈추지만_차단기는_열리지_않는다() {
        mock.respond(FakeMock.json(400, "{\"errorCode\":\"INVALID_REQUEST\",\"errorMessage\":\"qty\"}"));

        for (int i = 0; i < 5; i++) {
            assertThat(gateway.register("K-1", REQUEST).verdict()).isEqualTo(Verdict.REJECTED);
        }

        assertThat(rejectGuard.blocked()).isTrue();
        assertThat(gate.receivable(JobType.REGISTER, 0)).isZero();
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    void 취소된_키_거절은_연쇄_거절로_세지_않는다() {
        mock.respond(FakeMock.json(409, "{\"errorCode\":\"KEY_CANCELED\"}"));

        for (int i = 0; i < 10; i++) {
            gateway.register("K-1", REQUEST);
        }

        assertThat(rejectGuard.blocked()).isFalse();
    }

    @Test
    void mock_이_아닌_곳의_응답은_설정_오류이고_이어지면_받기를_멈춘다() {
        mock.respond(FakeMock.html(404));

        for (int i = 0; i < 5; i++) {
            MockResult result = gateway.cancel(REQUEST);
            assertThat(result.verdict()).isEqualTo(Verdict.MISCONFIGURED);
            assertThat(result.attemptResult()).isNull();
        }

        assertThat(rejectGuard.blocked()).isTrue();
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    void 받아도_되는_건수는_자리_수에서_처리_중인_건을_뺀_만큼이고_등록은_CANCEL_전용_자리를_뺀다() {
        assertThat(gate.receivable(JobType.CANCEL, 0)).isEqualTo(3);
        assertThat(gate.receivable(JobType.REGISTER, 0)).isEqualTo(2);
        assertThat(gate.receivable(JobType.REGISTER, 1)).isEqualTo(1);
        assertThat(gate.receivable(JobType.REGISTER, 5)).isZero();
    }

    @Test
    void 반쯤_열린_동안은_시험_호출_수만큼만_받는다() {
        mock.respond(FakeMock.json(500, UNAVAILABLE));
        for (int i = 0; i < 4; i++) {
            gateway.register("K-1", REQUEST);
        }

        await().atMost(Duration.ofSeconds(2)).until(circuit::isHalfOpen);

        assertThat(gate.receivable(JobType.CANCEL, 0)).as("자리는 최소 2 로 줄었다").isEqualTo(2);
        assertThat(gate.receivable(JobType.CANCEL, 1)).isEqualTo(1);
    }

    private void build(RateLimiterConfig rateLimit) {
        http = MockConfig.httpClient(Settings.mock(mock.baseUrl()), 4);
        MockClient client = new MockClient(MockConfig.restClient(http, Settings.mock(mock.baseUrl())), JSON, 65536);
        limiter = new ConcurrencyLimiter(Settings.concurrency(), Settings.SLOW_CALL, clock);
        circuit = new MockCircuit(Settings.circuit(), clock, config -> CircuitBreaker.of(MockCircuit.NAME, config),
                limiter::shrinkToMin);
        rejectGuard = new RejectGuard(Settings.rejectGuard(), clock);
        gateway = new MockGateway(client, circuit, RateLimiter.of(MockCircuit.NAME, rateLimit), limiter, rejectGuard,
                meters, clock);
        gate = new ReceiveGate(circuit, limiter, rejectGuard);
    }
}
