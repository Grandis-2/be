package com.grandis.nova.preorder.integration.mock;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 외부 예약 Mock — 정합성 조치(키로 Mock 취소)에서 키로 조회 · 취소를 부른다. 주소 속성 이름은 batch 와 같다(인프라가 이 이름으로 넣는다).
 * 관리자가 누르고 결과를 기다리는 호출이라 제한 시간을 짧게 둔다.
 *
 * @param baseUrl         필수. 없으면 기동 실패
 * @param connectTimeout  연결 제한 시간
 * @param readTimeout     응답을 다 받을 때까지의 제한 시간
 * @param maxResponseBody 응답 본문을 읽는 상한. 엉뚱한 서버가 큰 본문을 보내도 여기까지만 읽는다
 */
@ConfigurationProperties("external.mock")
public record ExternalMockProperties(
        URI baseUrl,
        @DefaultValue("300ms") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout,
        @DefaultValue("64KB") DataSize maxResponseBody
) {

    public ExternalMockProperties {
        if (baseUrl == null || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("external.mock.base-url 이 없다 — 키로 Mock 취소를 할 수 없다");
        }
        if (connectTimeout == null || !connectTimeout.isPositive() || readTimeout == null || !readTimeout.isPositive()) {
            throw new IllegalArgumentException("external.mock 의 제한 시간은 0 보다 커야 한다");
        }
        if (maxResponseBody == null || maxResponseBody.toBytes() < 1) {
            throw new IllegalArgumentException("external.mock.max-response-body 는 0 보다 커야 한다");
        }
    }
}
