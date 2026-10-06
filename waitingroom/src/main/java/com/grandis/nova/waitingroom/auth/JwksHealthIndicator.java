package com.grandis.nova.waitingroom.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 기동 뒤 JWKS 를 한 번이라도 받았는가. readiness 에 넣어 키 없이 트래픽을 받지 않게 한다.
 * 정적 공개키만 쓰면(jwk-set-uri 없음) 늘 UP 이다.
 */
@Component("jwksHealthIndicator")
class JwksHealthIndicator implements HealthIndicator {

    private final ObjectProvider<JwkSetCache> jwkSetCache;

    JwksHealthIndicator(ObjectProvider<JwkSetCache> jwkSetCache) {
        this.jwkSetCache = jwkSetCache;
    }

    @Override
    public Health health() {
        JwkSetCache cache = jwkSetCache.getIfAvailable();
        return cache == null || cache.loadedOnce() ? Health.up().build() : Health.down().build();
    }
}
