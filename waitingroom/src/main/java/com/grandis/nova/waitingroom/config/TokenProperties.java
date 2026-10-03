package com.grandis.nova.waitingroom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Instant;
import java.util.List;

/**
 * 입장권 · 대기 토큰 서명 키. preorder 와 같은 값이다(WAITING_TOKEN_*). 코드 · 이미지에 넣지 않는다.
 *
 * @param secret        현재 키. 16자 이상
 * @param previous      키 교체 중에만 받아 주는 이전 키. 최대 2개
 * @param rolloutEndsAt 키 교체 배포가 끝나는 시각. 이전 키를 받으려면 필수
 */
@ConfigurationProperties("waitingroom.token")
public record TokenProperties(String secret, @DefaultValue List<String> previous, Instant rolloutEndsAt) {

    public TokenProperties {
        // 환경 변수가 비어 있으면 빈 문자열 하나로 들어온다
        previous = previous == null ? List.of() : previous.stream().filter(key -> !key.isBlank()).toList();
    }
}
