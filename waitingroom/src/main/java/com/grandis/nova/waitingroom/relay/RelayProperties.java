package com.grandis.nova.waitingroom.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * @param preorderUri       접수를 전달할 preorder 주소. 로컬 기본값은 preorder 의 기본 포트다
 * @param preorderClockSkew preorder 가 입장권 발급 시각에서 빼는 오차(nova.admission-ticket.clock-skew)와 같은 값.
 *                          거절 뒤 재진입 대기(Retry-After)를 이 값 + 1초로 준다
 */
@ConfigurationProperties("waitingroom.relay")
public record RelayProperties(URI preorderUri, Duration preorderClockSkew) {

    public RelayProperties {
        preorderUri = preorderUri == null ? URI.create("http://localhost:8083") : preorderUri;
        preorderClockSkew = preorderClockSkew == null ? Duration.ofSeconds(30) : preorderClockSkew;
        if (preorderClockSkew.isNegative()) {
            throw new IllegalArgumentException("waitingroom.relay.preorder-clock-skew 는 0 이상이어야 한다: " + preorderClockSkew);
        }
    }
}
