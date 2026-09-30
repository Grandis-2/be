package com.grandis.nova.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토스 호출의 주소 · 시간 예산이 빠지거나 어긋나면 기동하지 않는다. 특히 read-timeout 이 없으면 JDK 구현은 응답을 기한 없이 기다린다.
 */
class TossHttpSettingsVerifierTest {

    static MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.http.serviceclient.toss.base-url", "https://api.tosspayments.com")
                .withProperty("spring.http.serviceclient.toss.connect-timeout", "3s")
                .withProperty("spring.http.serviceclient.toss.read-timeout", "60s")
                .withProperty("spring.http.serviceclient.toss.redirects", "dont-follow");
    }

    @Test
    void operatingValuesPass() {
        assertThatCode(() -> TossHttpSettingsVerifier.verify(valid())).doesNotThrowAnyException();
    }

    // 리스(70s)와 딱 맞는 합은 통과한다
    @Test
    void sumEqualToLeasePasses() {
        MockEnvironment environment = valid().withProperty("spring.http.serviceclient.toss.read-timeout", "67s");

        assertThatCode(() -> TossHttpSettingsVerifier.verify(environment)).doesNotThrowAnyException();
    }

    @Test
    void missingGroupFails() {
        assertThatThrownBy(() -> TossHttpSettingsVerifier.verify(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.http.serviceclient.toss");
    }

    @ParameterizedTest(name = "{0} 누락")
    @CsvSource({"base-url, base-url", "connect-timeout, connect-timeout", "read-timeout, read-timeout",
            "redirects, redirects"})
    void missingValueFails(String key, String message) {
        MockEnvironment environment = new MockEnvironment();
        for (String k : new String[]{"base-url", "connect-timeout", "read-timeout", "redirects"}) {
            if (!k.equals(key)) {
                environment.setProperty("spring.http.serviceclient.toss." + k,
                        valid().getProperty("spring.http.serviceclient.toss." + k));
            }
        }

        assertThatThrownBy(() -> TossHttpSettingsVerifier.verify(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
    }

    // http 면 Basic 인증(시크릿 키)이 평문으로 나간다. loopback(테스트 · 로컬 대역)만 예외다.
    @ParameterizedTest
    @CsvSource({"http://api.tosspayments.com", "http://10.0.0.5:8080", "ftp://api.tosspayments.com", "api.tosspayments.com",
            "https://"})
    void nonHttpsBaseUrlFails(String baseUrl) {
        MockEnvironment environment = valid().withProperty("spring.http.serviceclient.toss.base-url", baseUrl);

        assertThatThrownBy(() -> TossHttpSettingsVerifier.verify(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
    }

    @ParameterizedTest
    @CsvSource({"https://api.tosspayments.com", "http://127.0.0.1:18080", "http://localhost:18080", "http://[::1]:18080"})
    void httpsOrLoopbackPasses(String baseUrl) {
        MockEnvironment environment = valid().withProperty("spring.http.serviceclient.toss.base-url", baseUrl);

        assertThatCode(() -> TossHttpSettingsVerifier.verify(environment)).doesNotThrowAnyException();
    }

    // 전역 spring.http.clients.* 만 두면 막힌다 — 메시지가 그룹에 적으라고 알려 준다.
    @Test
    void globalTimeoutsAloneAreNotAccepted() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.http.serviceclient.toss.base-url", "https://api.tosspayments.com")
                .withProperty("spring.http.serviceclient.toss.redirects", "dont-follow")
                .withProperty("spring.http.clients.connect-timeout", "3s")
                .withProperty("spring.http.clients.read-timeout", "60s");

        assertThatThrownBy(() -> TossHttpSettingsVerifier.verify(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("토스 그룹에 직접 적는다");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"read-timeout, 0s", "connect-timeout, 0s", "read-timeout, 68s", "redirects, follow",
            "redirects, follow-when-possible"})
    void invalidValueFails(String key, String value) {
        MockEnvironment environment = valid().withProperty("spring.http.serviceclient.toss." + key, value);

        assertThatThrownBy(() -> TossHttpSettingsVerifier.verify(environment)).isInstanceOf(IllegalStateException.class);
    }
}
