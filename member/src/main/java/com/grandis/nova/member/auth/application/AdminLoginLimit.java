package com.grandis.nova.member.auth.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 관리자 로그인 시도 제한. `auth.admin-login-limit.*`.
 *
 * @param maxAttempts      창 안에서 한 IP 가 할 수 있는 시도 수(기본 5). 넘으면 bcrypt 없이 429
 * @param window           창 길이(기본 1분). 첫 시도부터 센다
 * @param trustedProxyHops X-Forwarded-For 에서 믿는 프록시 수(기본 2 — CloudFront · ALB). 로컬 직접 접속은 0 도 된다
 * @param maxTotalAttempts 창 안에서 모든 IP 를 합친 시도 수(기본 60). IP 를 바꿔 가며 오는 공격에도 bcrypt 부하에 천장을 둔다.
 *                         평소 관리자 로그인은 분에 몇 번이라 닿지 않는 값이다. 성공하면 0 으로 돌아간다
 */
@ConfigurationProperties("auth.admin-login-limit")
public record AdminLoginLimit(@DefaultValue("5") int maxAttempts, @DefaultValue("1m") Duration window,
                              @DefaultValue("2") int trustedProxyHops, @DefaultValue("60") int maxTotalAttempts) {

    public AdminLoginLimit {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("auth.admin-login-limit.max-attempts must be positive, was " + maxAttempts);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("auth.admin-login-limit.window must be positive, was " + window);
        }
        if (maxTotalAttempts <= 0) {
            throw new IllegalArgumentException("auth.admin-login-limit.max-total-attempts must be positive, was " + maxTotalAttempts);
        }
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("auth.admin-login-limit.trusted-proxy-hops must be >= 0, was " + trustedProxyHops);
        }
    }
}
