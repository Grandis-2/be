package com.grandis.nova.preorder.integration;

import com.grandis.nova.common.security.BearerTokenRelayInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

@Configuration(proxyBeanMethods = false)
class TokenRelayConfig {

    /** catalog · order 내부 호출에 지금 요청한 사용자의 토큰을 싣는다. 받는 쪽이 토큰 주인을 확인한다. */
    @Bean
    RestClientHttpServiceGroupConfigurer tokenRelay() {
        BearerTokenRelayInterceptor interceptor = new BearerTokenRelayInterceptor();
        return groups -> groups.filterByName(Dependencies.ALL.toArray(String[]::new))
                .forEachClient((group, builder) -> builder.requestInterceptor(interceptor));
    }
}
