package com.grandis.nova.payment.config;

import com.grandis.nova.payment.client.toss.TossAuthorization;
import com.grandis.nova.payment.client.toss.TossPaymentsApi;
import com.grandis.nova.payment.client.toss.TossProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * 외부 HTTP 클라이언트(토스페이먼츠). 주소 · 타임아웃은 spring.http.serviceclient.&lt;그룹&gt; 설정으로 준다(D9).
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = TossAuthorization.GROUP, types = TossPaymentsApi.class)
@EnableConfigurationProperties(TossProperties.class)
public class HttpClientConfig {

    /**
     * HTTP 구현은 JDK HttpClient 로 고정한다. 정하지 않으면 스프링 부트가 클래스패스를 보고 고른다 — 다른 의존(예: AWS SDK 의
     * apache5-client)이 딸려 오는 순간 Apache HttpClient 5 로 조용히 바뀌고, 그 기본 풀(주소당 5개 · 대기 최대 3분)이
     * 호출 시간 예산(연결 3s + 읽기 60s ≤ 리스 70s, D12)을 깬다. JDK 구현은 풀 상한 · 풀 대기가 없어 예산이 그대로다.
     * 토스 동시 호출 상한(격벽)은 이 구현과 별개로 NV-101 · 드로우 설계 때 앱 수준에서 둔다(2026-09-30 사용자 결정).
     * 이 빈이 있으면 spring.http.clients.imperative.factory 설정은 무시된다.
     */
    @Bean
    ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.jdk();
    }

    /** Basic 인증은 토스 그룹에만 싣는다 — 다른 클라이언트로 시크릿 키가 새지 않게 그룹 이름으로 거른다. */
    @Bean
    RestClientHttpServiceGroupConfigurer tossAuthorization(TossProperties properties) {
        return groups -> groups.filterByName(TossAuthorization.GROUP)
                .forEachClient((group, builder) -> TossAuthorization.apply(builder, properties));
    }
}
