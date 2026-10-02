package com.grandis.nova.member.auth.api;

import com.grandis.nova.member.auth.application.AdminLoginLimit;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * {@link ClientIps} 를 만들기 전에 서블릿 컨테이너가 X-Forwarded-For 를 먼저 손대지 않는지 본다.
 *
 * <p><b>ClientIps 가 칸을 세려면 헤더가 받은 그대로여야 한다.</b> `server.forward-headers-strategy` 가 켜지면 Tomcat RemoteIpValve 가 사설 IP(ALB)
 * 칸을 떼고 헤더를 고쳐 쓴다. 그 위에서 오른쪽 두 번째를 다시 세면 위조한 값으로 세거나(우회) CloudFront 엣지 IP 로 모두 묶인다(잠금) — 리뷰
 * 실측(실제 Tomcat, `AWS_EXECUTION_ENV=AWS_ECS_FARGATE`). Boot 는 이 값을 명시하지 않아도 ECS 를 감지하면 스스로 켠다(CloudPlatform.AWS_ECS 의
 * isUsingForwardHeaders). 그래서 믿는 프록시 수가 1 이상이면 전략이 명시적으로 `none` 이어야 하고, 아니면 기동을 멈춘다.
 */
@Configuration(proxyBeanMethods = false)
public class ClientIpsConfiguration {

    static final String STRATEGY = "server.forward-headers-strategy";

    @Bean
    ClientIps clientIps(AdminLoginLimit limit, Environment environment) {
        if (limit.trustedProxyHops() > 0 && containerRewritesForwardedHeaders(environment)) {
            throw new IllegalStateException("auth.admin-login-limit.trusted-proxy-hops=" + limit.trustedProxyHops()
                    + " requires " + STRATEGY + "=none — the container would rewrite X-Forwarded-For before ClientIps reads it"
                    + " (on AWS ECS Spring Boot turns it on by default)");
        }
        return new ClientIps(limit.trustedProxyHops());
    }

    /** 명시한 값이 있으면 그것, 없으면 Boot 가 감지한 클라우드의 기본값(ECS 는 켬)을 따른다. */
    static boolean containerRewritesForwardedHeaders(Environment environment) {
        String strategy = environment.getProperty(STRATEGY);
        if (strategy == null || strategy.isBlank()) {
            CloudPlatform platform = CloudPlatform.getActive(environment);
            return platform != null && platform.isUsingForwardHeaders();
        }
        return !"none".equalsIgnoreCase(strategy.strip());
    }
}
