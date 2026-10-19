package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.security.BearerTokenRelayInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * order(리뷰 자격 · 드로우 증정품) · member 내부 API 클라이언트. 호출에 지금 요청한 사용자의 토큰을 싣는다 — 받는 쪽이 토큰 주인을 확인한다(preorder 와 같은 방식).
 * order 의 재고 조회는 토큰을 받지 않는 API 라 릴레이 없는 그룹({@link Dependencies#ORDER_STOCKS})에 따로 둔다.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = Dependencies.ORDER, types = {OrderClient.class, DrawGiftClient.class})
@ImportHttpServices(group = Dependencies.MEMBER, types = MemberClient.class)
@ImportHttpServices(group = Dependencies.ORDER_STOCKS, types = StockClient.class)
class InternalClientConfig {

    @Bean
    RestClientHttpServiceGroupConfigurer tokenRelay() {
        BearerTokenRelayInterceptor interceptor = new BearerTokenRelayInterceptor();
        return groups -> groups.filterByName(Dependencies.ALL.toArray(String[]::new))
                .forEachClient((group, builder) -> builder.requestInterceptor(interceptor));
    }

    /** 스프링 부트가 RestClient.Builder · HTTP 서비스 그룹 클라이언트의 관측에 이 규칙을 쓴다(없으면 기본 규칙). 같은 타입 빈이 둘이면 기동이 실패한다. */
    @Bean
    PathOnlyUriTagConvention pathOnlyUriTagConvention(ObjectProvider<ObservationProperties> properties) {
        return new PathOnlyUriTagConvention(
                properties.getIfAvailable(ObservationProperties::new).getHttp().getClient().getRequests().getName());
    }
}
