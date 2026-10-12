package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.config.WorkerProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class MockConfig {

    static final String LIMIT_METRIC = "worker.concurrency.limit";
    static final String IN_USE_METRIC = "worker.concurrency.in-use";
    static final String BLOCKED_METRIC = "worker.receive.blocked";

    @Bean(destroyMethod = "close")
    CloseableHttpClient mockHttpClient(WorkerProperties properties) {
        return httpClient(properties.mock(), properties.concurrency().limit());
    }

    @Bean
    MockClient mockClient(CloseableHttpClient mockHttpClient, WorkerProperties properties, JsonMapper json) {
        return new MockClient(restClient(mockHttpClient, properties.mock()), json,
                (int) properties.mock().maxResponseBody().toBytes());
    }

    @Bean
    ConcurrencyLimiter concurrencyLimiter(WorkerProperties properties, Clock clock, MeterRegistry meters) {
        ConcurrencyLimiter limiter =
                new ConcurrencyLimiter(properties.concurrency(), properties.mock().slowCallThreshold(), clock);
        Gauge.builder(LIMIT_METRIC, limiter, ConcurrencyLimiter::limit).register(meters);
        Gauge.builder(IN_USE_METRIC, limiter, ConcurrencyLimiter::inUse).register(meters);
        return limiter;
    }

    /** 레지스트리가 만들어야 차단기 상태 · 전이 지표가 나간다. 열리면 자리를 최소로 줄인다. */
    @Bean
    MockCircuit mockCircuit(WorkerProperties properties, Clock clock, CircuitBreakerRegistry registry,
                            ConcurrencyLimiter limiter) {
        return new MockCircuit(properties.circuit(), properties.mock().slowCallThreshold(), clock,
                config -> registry.circuitBreaker(MockCircuit.NAME, config), limiter::shrinkToMin);
    }

    @Bean
    RejectGuard rejectGuard(WorkerProperties properties, Clock clock, MeterRegistry meters) {
        RejectGuard guard = new RejectGuard(properties.rejectGuard(), clock);
        Gauge.builder(BLOCKED_METRIC, guard, g -> g.blocked() ? 1 : 0).register(meters);
        return guard;
    }

    @Bean
    MockGateway mockGateway(MockClient client, MockCircuit circuit, RateLimiterRegistry registry,
                            ConcurrencyLimiter limiter, RejectGuard rejectGuard, WorkerProperties properties,
                            MeterRegistry meters, Clock clock) {
        RateLimiter rateLimiter = registry.rateLimiter(MockCircuit.NAME, rateLimit(properties.concurrency()));
        return new MockGateway(client, circuit, rateLimiter, limiter, rejectGuard, meters, clock);
    }

    @Bean
    ReceiveGate receiveGate(MockCircuit circuit, ConcurrencyLimiter limiter, RejectGuard rejectGuard) {
        return new ReceiveGate(circuit, limiter, rejectGuard);
    }

    /** 초당 상한. mock 이 아주 빠를 때만 걸린다. 자리가 날 때까지 permitWait 만 기다린다. */
    static RateLimiterConfig rateLimit(WorkerProperties.Concurrency settings) {
        return RateLimiterConfig.custom()
                .limitForPeriod(settings.rateLimit())
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(settings.permitWait())
                .build();
    }

    /**
     * 연결은 풀에서 다시 쓰되 최대 수명까지만 쓴다 — 오래 붙들면 새로 뜬 mock 에 요청이 가지 않고 한 대에만 몰린다.
     * 쉬던 연결은 쓰기 전에 살아 있는지 본다. 내려간 mock 이 끊은 연결로 보내면 결과를 모르는 실패가 된다.
     */
    static CloseableHttpClient httpClient(WorkerProperties.Mock settings, int maxConnections) {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(settings.connectTimeout()))
                                .setTimeToLive(TimeValue.of(settings.connectionMaxLifetime()))
                                .setValidateAfterInactivity(TimeValue.of(settings.validateAfterInactivity()))
                                .build())
                        .setMaxConnTotal(maxConnections)
                        .setMaxConnPerRoute(maxConnections)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(settings.readTimeout()))
                        // 풀이 비기를 기본값(3분)만큼 기다리지 않는다
                        .setConnectionRequestTimeout(Timeout.of(settings.connectTimeout()))
                        .build())
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.of(settings.connectionMaxLifetime()))
                .disableAutomaticRetries()
                .disableRedirectHandling()
                // 쓰지 않는 처리(쿠키 · 인증 캐시 · 압축 해제)를 요청마다 거치지 않는다
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .build();
    }

    static RestClient restClient(CloseableHttpClient http, WorkerProperties.Mock settings) {
        return RestClient.builder()
                .baseUrl(settings.baseUrl().toString())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(http))
                .build();
    }
}
