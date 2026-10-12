package com.grandis.nova.batch.reconciliation.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Mock 원장 목록 조회(GET /external/reservations). 주소 속성 이름은 preorder · worker 와 같다(인프라가 이 이름으로 넣는다).
 *
 * @param baseUrl         필수. 없으면 기동 실패
 * @param connectTimeout  연결 제한 시간
 * @param readTimeout     한 페이지 응답을 다 받을 때까지의 제한 시간
 * @param pageSize        한 페이지 건수. Mock 계약은 1~1000
 * @param maxResponseBody 한 페이지 본문을 읽는 상한. 엉뚱한 서버가 큰 본문을 보내도 여기까지만 읽는다
 */
@ConfigurationProperties("external.mock")
public record MockLedgerProperties(
        URI baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("1000") int pageSize,
        @DefaultValue("8MB") DataSize maxResponseBody
) {

    public MockLedgerProperties {
        if (baseUrl == null || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("external.mock.base-url 이 없다 — Mock 원장을 읽을 수 없다");
        }
        if (connectTimeout == null || !connectTimeout.isPositive() || readTimeout == null || !readTimeout.isPositive()) {
            throw new IllegalArgumentException("external.mock 의 제한 시간은 0 보다 커야 한다");
        }
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("external.mock.page-size 는 1~1000 이어야 한다(Mock 계약): " + pageSize);
        }
        if (maxResponseBody == null || maxResponseBody.toBytes() < 1) {
            throw new IllegalArgumentException("external.mock.max-response-body 는 0 보다 커야 한다");
        }
    }
}
