package com.grandis.nova.common.security;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각은 전부 이 Clock 빈에서 온다. 발급 iat·exp, 파싱의 만료 판정, nbf 비교 모두.
 * 테스트가 다른 시계를 끼워 만료 경계를 태울 수 있어야 하므로 Instant.now() 를 직접 부르지 않는다.
 *
 * 실측(JwtConfigurationTest): 이 클래스는 자동설정이 아니라 컴포넌트 스캔되는 일반 설정이라
 * @ConditionalOnMissingBean 이 동작하지 않는다 — 앱이 Clock 을 또 정의하면 빈이 둘이 됐다.
 * 그래서 조건 없이 하나만 두고, 앱이나 테스트가 바꿔 끼울 때는 @Primary 로 덮는다. @Primary 없이 하나 더 두면 기동이 실패한다 — 이 설정의
 * JwtKeyRing 빈이 Clock 을 주입받기 때문에 주입받는 빈이 항상 있다(실측: JwtConfigurationTest.appDefinedClockWithoutPrimary,
 * "expected single matching bean but found 2". RS256 전환 전에는 JwtTokenProvider 가 있어야만 실패했다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 서명·검증 키. JWKS 를 받는 HTTP 클라이언트는 JDK HttpClient 로 직접 건다(연결 2s · 읽기 3s) — Boot 의 RestClient 빌더 빈을 안 쓴다(카카오 클라이언트와 같은 이유).
     * 받기는 링이 가진 데몬 스레드에서만 돈다(주기 5분 + 모르는 kid 때 비동기 1회). 요청 스레드는 캐시만 읽는다. 컨텍스트가 닫히면 AutoCloseable 로 그 스레드가 멈춘다.
     */
    @Bean
    public JwtKeyRing jwtKeyRing(JwtProperties properties, Clock clock) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofSeconds(3));
        return JwtKeyRing.withBackgroundRefresh(properties, clock, org.springframework.web.client.RestClient.builder().requestFactory(factory).build());
    }
}
