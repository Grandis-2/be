package com.grandis.nova.member.adminalert;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * 관리자 알림 대상 이름을 받는 HTTP 서비스 클라이언트(order 의 HttpClientConfig 와 같은 구성). 주소 · 시간 제한은 spring.http.serviceclient.&lt;그룹&gt;
 * 으로 준다 — 주소는 인프라 calls 가, 시간 제한 기본값은 MemberDefaults 가 넣는다.
 *
 * HTTP 구현은 JDK HttpClient 로 고정한다(order D9 와 같은 까닭). 정하지 않으면 common:sqs 의 AWS SDK 가 끌어온 Apache HttpClient 5 가 골라지고,
 * 그 기본 풀(주소당 5개 · 대기 최대 3분)이 시간 제한보다 오래 기다리게 한다. 카카오 클라이언트는 자기 요청 팩토리를 따로 만들어 이 빈과 상관없다.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = TargetNames.CATALOG, types = TargetClients.CatalogProductClient.class)
@ImportHttpServices(group = TargetNames.ORDER, types = TargetClients.DrawClient.class)
class AdminAlertHttpClientConfig {

    @Bean
    ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.jdk();
    }
}
