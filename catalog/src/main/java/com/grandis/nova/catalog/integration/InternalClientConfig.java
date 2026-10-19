package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.security.BearerTokenRelayInterceptor;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * order(리뷰 자격 · 드로우 증정품) · member 내부 API 클라이언트. 호출에 지금 요청한 사용자의 토큰을 싣는다 — 받는 쪽이 토큰 주인을 확인한다(preorder 와 같은 방식).
 * order 의 재고 조회 · preorder 의 회차 일정은 토큰을 받지 않는 API 라 릴레이 없는 그룹({@link Dependencies#ORDER_STOCKS} · {@link Dependencies#PREORDER})에 따로 둔다.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = Dependencies.ORDER, types = {OrderClient.class, DrawGiftClient.class})
@ImportHttpServices(group = Dependencies.MEMBER, types = MemberClient.class)
@ImportHttpServices(group = Dependencies.ORDER_STOCKS, types = StockClient.class)
@ImportHttpServices(group = Dependencies.PREORDER, types = PreorderCampaignClient.class)
class InternalClientConfig {

    @Bean
    RestClientHttpServiceGroupConfigurer tokenRelay() {
        BearerTokenRelayInterceptor interceptor = new BearerTokenRelayInterceptor();
        return groups -> groups.filterByName(Dependencies.ALL.toArray(String[]::new))
                .forEachClient((group, builder) -> builder.requestInterceptor(interceptor));
    }

    /**
     * HTTP 구현은 JDK HttpClient 로 고정한다(order HttpClientConfig 와 같은 결정). 정하지 않으면 스프링 부트가 클래스패스를 보고 고르는데, common:sqs 의
     * AWS SDK 가 끌어온 Apache HttpClient 5 가 된다 — 그 기본 재시도는 503 을 1초 기다려 한 번 더 보내(실측: preorder 503 한 번에 두 번 · 1.4초, 500 은
     * 한 번 · 14ms) 관리자 수정이 상품 행을 잠근 채 그만큼 더 기다리고, 기본 풀(주소당 5개)은 목록마다 부르는 재고 조회를 줄 세운다. JDK 구현은 재시도 ·
     * 풀 상한이 없다. 이 빈이 있으면 spring.http.clients.imperative.factory 설정은 무시된다.
     */
    @Bean
    ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.jdk();
    }

    /** 스프링 부트가 RestClient.Builder · HTTP 서비스 그룹 클라이언트의 관측에 이 규칙을 쓴다(없으면 기본 규칙). 같은 타입 빈이 둘이면 기동이 실패한다. */
    @Bean
    PathOnlyUriTagConvention pathOnlyUriTagConvention(ObjectProvider<ObservationProperties> properties) {
        return new PathOnlyUriTagConvention(
                properties.getIfAvailable(ObservationProperties::new).getHttp().getClient().getRequests().getName());
    }
}
