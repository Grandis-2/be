package com.grandis.nova.order.config;

import com.grandis.nova.order.client.payment.PaymentClient;
import com.grandis.nova.order.client.payment.PaymentConfirmClient;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.preorder.PreorderClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * 다른 서비스 내부 API 클라이언트. 주소 · 타임아웃은 spring.http.serviceclient.&lt;그룹&gt; 설정으로 준다.
 * 결제 승인은 준비와 그룹을 나눈다 — payment 가 토스를 최대 60초 기다려 읽기 기한이 길다(payment-confirm).
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "preorder", types = PreorderClient.class)
@ImportHttpServices(group = "catalog", types = CatalogClient.class)
@ImportHttpServices(group = "payment", types = PaymentClient.class)
@ImportHttpServices(group = "payment-confirm", types = PaymentConfirmClient.class)
public class HttpClientConfig {

    /**
     * HTTP 구현은 JDK HttpClient 로 고정한다(payment 와 같은 결정, D9). 정하지 않으면 스프링 부트가 클래스패스를 보고 고르는데,
     * common:sqs 의 AWS SDK 가 apache5-client 를 끌어와 Apache HttpClient 5 가 된다 — 그 기본 풀(주소당 5개 · 대기 최대 3분)은
     * 결제 승인(읽기 70초)이 몇 건만 겹쳐도 다음 호출을 풀 대기에 묶는다. JDK 구현은 풀 상한 · 풀 대기가 없다.
     * 이 빈이 있으면 spring.http.clients.imperative.factory 설정은 무시된다.
     */
    @Bean
    ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.jdk();
    }
}
