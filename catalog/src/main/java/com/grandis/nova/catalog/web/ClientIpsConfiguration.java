package com.grandis.nova.catalog.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 집계 신호(조회 · 검색)의 IP 별 익명 id 상한이 쓰는 {@link ClientIps}. member 의 같은 가드를 따른다 — 서블릿 컨테이너가 X-Forwarded-For 를 먼저
 * 고쳐 쓰면(Tomcat RemoteIpValve) 오른쪽에서 센 칸이 위조한 값이나 CloudFront 엣지 IP 가 된다. Boot 는 ECS 를 감지하면 전략을 비워 둬도 스스로 켜므로,
 * 믿는 프록시 수가 1 이상이면 `server.forward-headers-strategy` 가 명시적으로 `none` 이어야 하고 아니면 기동을 멈춘다.
 * catalog 는 요청의 스킴 · 호스트를 쓰는 곳이 없어 전략을 끄더라도 바뀌는 응답이 없다.
 */
@Configuration(proxyBeanMethods = false)
public class ClientIpsConfiguration {

    static final String STRATEGY = "server.forward-headers-strategy";
    static final String HOPS = "nova.signals.trusted-proxy-hops";

    /** @param trustedProxyHops X-Forwarded-For 에서 믿는 프록시 수. 운영은 CloudFront → ALB 라 2(member 와 같다). 0 이면 헤더를 보지 않는다 */
    @Bean
    ClientIps clientIps(@Value("${" + HOPS + ":2}") int trustedProxyHops, Environment environment) {
        if (trustedProxyHops > 0 && containerRewritesForwardedHeaders(environment)) {
            throw new IllegalStateException(HOPS + "=" + trustedProxyHops + " requires " + STRATEGY
                    + "=none — the container would rewrite X-Forwarded-For before ClientIps reads it (on AWS ECS Spring Boot turns it on by default)");
        }
        return new ClientIps(trustedProxyHops);
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
