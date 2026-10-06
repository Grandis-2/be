package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.security.BearerTokenRelayInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

/** order · member 내부 API 클라이언트. 호출에 지금 요청한 사용자의 토큰을 싣는다 — 받는 쪽이 토큰 주인을 확인한다(preorder 와 같은 방식). */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = Dependencies.ORDER, types = OrderClient.class)
@ImportHttpServices(group = Dependencies.MEMBER, types = MemberClient.class)
class InternalClientConfig {

    @Bean
    RestClientHttpServiceGroupConfigurer tokenRelay() {
        BearerTokenRelayInterceptor interceptor = new BearerTokenRelayInterceptor();
        return groups -> groups.filterByName(Dependencies.ALL.toArray(String[]::new))
                .forEachClient((group, builder) -> builder.requestInterceptor(interceptor));
    }
}
