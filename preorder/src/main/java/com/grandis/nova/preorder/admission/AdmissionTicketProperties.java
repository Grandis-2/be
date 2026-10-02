package com.grandis.nova.preorder.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 입장권 검증 설정. 비밀은 게이트웨이와 같은 값이다(WAITING_TOKEN_*). 코드 · 이미지에 넣지 않는다.
 *
 * @param secret        현재 키. 16자 이상
 * @param previous      키 교체 중에만 받아 주는 이전 키. 최대 2개
 * @param rolloutEndsAt 키 교체 배포가 끝나는 시각. 이전 키를 받으려면 필수
 * @param ttl           게이트웨이가 정한 입장권 수명. 이전 키를 받아 주는 기간을 계산하는 데만 쓴다
 * @param window        게이트웨이가 만료를 끊는 단위
 * @param clockSkew     서버 시각 오차 허용
 */
@ConfigurationProperties("nova.admission-ticket")
record AdmissionTicketProperties(
        String secret,
        @DefaultValue List<String> previous,
        Instant rolloutEndsAt,
        @DefaultValue("120s") Duration ttl,
        @DefaultValue("30s") Duration window,
        @DefaultValue("30s") Duration clockSkew
) {

    public AdmissionTicketProperties {
        previous = previous == null ? List.of() : previous.stream().filter(key -> !key.isBlank()).toList();
    }
}
