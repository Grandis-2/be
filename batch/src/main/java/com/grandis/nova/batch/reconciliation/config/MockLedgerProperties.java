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
 * @param maxDuration     목록 전체를 받는 기한. 검사 트랜잭션(스냅샷)이 Mock 응답을 기다리는 동안 열려 있으므로 끝없이 늘지 않게 한다
 * @param retries         한 페이지를 일시 실패(응답을 받지 못함 · 5xx) 뒤 다시 받는 횟수. 0 이면 다시 받지 않는다.
 *                        하루 한 번 도는 검사가 일시 실패 한 번에 판정 불가가 되지 않게 한다
 * @param retryDelay      처음 다시 받기 전에 기다리는 시간. 다음부터는 두 배씩. 기다리는 시간도 목록 전체 기한 안에 든다
 */
@ConfigurationProperties("external.mock")
public record MockLedgerProperties(
        URI baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("1000") int pageSize,
        @DefaultValue("8MB") DataSize maxResponseBody,
        @DefaultValue("10m") Duration maxDuration,
        @DefaultValue("2") int retries,
        @DefaultValue("1s") Duration retryDelay
) {

    public MockLedgerProperties {
        if (baseUrl == null || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("external.mock.base-url 이 없다 — Mock 원장을 읽을 수 없다");
        }
        if (connectTimeout == null || !connectTimeout.isPositive() || readTimeout == null || !readTimeout.isPositive()
                || maxDuration == null || !maxDuration.isPositive()) {
            throw new IllegalArgumentException("external.mock 의 제한 시간은 0 보다 커야 한다");
        }
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("external.mock.page-size 는 1~1000 이어야 한다(Mock 계약): " + pageSize);
        }
        if (maxResponseBody == null || maxResponseBody.toBytes() < 1) {
            throw new IllegalArgumentException("external.mock.max-response-body 는 0 보다 커야 한다");
        }
        if (retries < 0 || retryDelay == null || !retryDelay.isPositive()) {
            throw new IllegalArgumentException("external.mock.retries 는 0 이상, retry-delay 는 0 보다 커야 한다");
        }
    }
}
