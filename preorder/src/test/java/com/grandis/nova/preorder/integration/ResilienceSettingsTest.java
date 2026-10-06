package com.grandis.nova.preorder.integration;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.service.registry.HttpServiceProxyRegistry;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 코드 기본값(PreorderDefaults)의 장애 대응 설정이 의존 서비스마다 그대로 적용되는지. */
@PreorderIntegrationTest
class ResilienceSettingsTest {

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @Autowired
    RetryRegistry retries;

    @Autowired
    BulkheadRegistry bulkheads;

    @Autowired
    HttpServiceProxyRegistry httpServices;

    /** 토큰 릴레이 · 장애 대응 설정은 그룹 이름으로 붙는다. 이름이 어긋나면 아무 클라이언트에도 붙지 않고 조용히 빠진다. */
    @Test
    void 의존_서비스_이름이_실제_HTTP_클라이언트_그룹_이름과_같다() {
        assertThat(httpServices.getGroupNames()).containsAll(Dependencies.ALL);
    }

    @ParameterizedTest
    @ValueSource(strings = {Dependencies.CATALOG, Dependencies.ORDER})
    void 재시도는_연결_실패와_5xx_만_한_번(String dependency) {
        RetryConfig retry = retries.retry(dependency).getRetryConfig();

        assertThat(retry.getMaxAttempts()).isEqualTo(2);
        assertThat(retry.getExceptionPredicate().test(new ResourceAccessException("timeout"))).isTrue();
        assertThat(retry.getExceptionPredicate().test(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", null, null, null))).isTrue();
        assertThat(retry.getExceptionPredicate().test(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", null, null, null))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {Dependencies.CATALOG, Dependencies.ORDER})
    void 서킷은_50건_중_절반_실패_또는_1초_넘는_호출에_10초_열린다(String dependency) {
        CircuitBreakerConfig circuitBreaker = circuitBreakers.circuitBreaker(dependency).getCircuitBreakerConfig();

        assertThat(circuitBreaker.getSlidingWindowSize()).isEqualTo(50);
        assertThat(circuitBreaker.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(circuitBreaker.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(1));
        assertThat(circuitBreaker.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000L);
        assertThat(circuitBreaker.getIgnoreExceptionPredicate().test(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "", null, null, null))).isTrue();
        assertThat(circuitBreaker.getIgnoreExceptionPredicate().test(
                BulkheadFullException.createBulkheadFullException(bulkheads.bulkhead(dependency)))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {Dependencies.CATALOG, Dependencies.ORDER})
    void 동시_호출은_20_이고_기다리지_않는다(String dependency) {
        assertThat(bulkheads.bulkhead(dependency).getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);
        assertThat(bulkheads.bulkhead(dependency).getBulkheadConfig().getMaxWaitDuration()).isZero();
    }
}
