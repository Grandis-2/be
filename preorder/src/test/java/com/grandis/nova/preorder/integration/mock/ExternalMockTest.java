package com.grandis.nova.preorder.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.preorder.integration.DependencyGuard;
import com.grandis.nova.preorder.support.FakeExternalMock;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 설정(ExternalMockClientConfig)이 만든 클라이언트로 가짜 Mock 을 부른다. 응답 해석과 실패 문구를 본다 */
class ExternalMockTest {

    private static final FakeExternalMock MOCK = FakeExternalMock.start();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExternalMock externalMock = client(MOCK.url(), CircuitBreakerRegistry.ofDefaults());

    @BeforeEach
    void setUp() {
        MOCK.reset();
    }

    @AfterAll
    static void tearDown() {
        MOCK.close();
    }

    @Test
    void 키로_조회는_활성_취소_기록_없음을_가른다() {
        MOCK.active("k-active");
        MOCK.canceled("k-canceled");

        assertThat(externalMock.lookup("k-active")).isEqualTo(MockKeyState.ACTIVE);
        assertThat(externalMock.lookup("k-canceled")).isEqualTo(MockKeyState.CANCELED);
        assertThat(externalMock.lookup("k-none")).isEqualTo(MockKeyState.ABSENT);
    }

    @Test
    void 키로_취소는_번호_없이_사유를_실어_보낸다() {
        MOCK.active("k-1");

        externalMock.cancel("k-1", ExternalMock.GHOST_COMPENSATION);

        JsonNode sent = MOCK.cancels().getFirst();
        assertThat(sent.get("externalKey").asString()).isEqualTo("k-1");
        assertThat(sent.get("reservationNo").isNull()).isTrue();
        assertThat(sent.get("reason").asString()).isEqualTo("GHOST_COMPENSATION");
        assertThat(MOCK.state("k-1")).isEqualTo("CANCELED");
    }

    @Test
    void 취소가_200_이_아니면_계약과_다름으로_던진다() {
        MOCK.cancelAnswers(503);

        assertThatThrownBy(() -> externalMock.cancel("k-1", ExternalMock.GHOST_COMPENSATION))
                .isInstanceOf(ExternalMockUnavailable.class)
                .hasMessage("Mock 키로 취소 응답이 계약과 다름(HTTP 503)");
    }

    @Test
    void 경로가_없는_404_는_기록_없음이_아니다() {
        ExternalMock wrongPath = client(MOCK.url() + "/wrong", CircuitBreakerRegistry.ofDefaults());

        assertThatThrownBy(() -> wrongPath.lookup("k-1"))
                .isInstanceOf(ExternalMockUnavailable.class)
                .hasMessage("Mock 키로 조회 응답이 계약과 다름(HTTP 404)");
    }

    @Test
    void 닿지_않으면_연결_실패로_던지고_주소는_싣지_않는다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ExternalMock unreachable = client("http://localhost:" + closedPort, CircuitBreakerRegistry.ofDefaults());

        assertThatThrownBy(() -> unreachable.lookup("k-1"))
                .isInstanceOf(ExternalMockUnavailable.class)
                .hasMessage("Mock 키로 조회 응답을 받지 못함 — 연결 실패");
    }

    @Test
    void 회로가_열리면_부르지_않고_바로_실패로_던진다() {
        CircuitBreakerRegistry opensAfterOneFailure = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(1).minimumNumberOfCalls(1).failureRateThreshold(100).build());
        ExternalMock guarded = client(MOCK.url(), opensAfterOneFailure);
        MOCK.cancelAnswers(503);

        assertThatThrownBy(() -> guarded.cancel("k-1", ExternalMock.GHOST_COMPENSATION))
                .hasMessage("Mock 키로 취소 응답이 계약과 다름(HTTP 503)");
        assertThatThrownBy(() -> guarded.lookup("k-1"))
                .isInstanceOf(ExternalMockUnavailable.class)
                .hasMessage("Mock 호출을 잠시 막음(회로 열림 · 동시 호출 상한) — 잠시 뒤 다시");

        assertThat(MOCK.lookups()).isZero();
        assertThat(MOCK.cancels()).hasSize(1);
    }

    /** 운영과 같은 DependencyGuard. 재시도는 끈다 — Mock 실패는 RestClient 예외가 아니라 운영 기본값으로도 다시 보내지 않는다 */
    private static ExternalMock client(String baseUrl, CircuitBreakerRegistry circuitBreakers) {
        ExternalMockProperties properties = new ExternalMockProperties(URI.create(baseUrl), Duration.ofMillis(300),
                Duration.ofSeconds(2), DataSize.ofKilobytes(64));
        DependencyGuard guard = new DependencyGuard(circuitBreakers,
                RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build()), BulkheadRegistry.ofDefaults());
        return new ExternalMockClientConfig().externalMock(properties, JSON, guard);
    }
}
