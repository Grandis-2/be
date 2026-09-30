package com.grandis.nova.order.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * nova.outbox.transport(전송 구현 선택)는 여기 없다 — 구현 빈의 조건으로만 읽는다. 없으면 전송 구현이 없어 기동이 실패한다.
 * nova.outbox.relay-interval 은 {@link OutboxRelay} 의 스케줄이 읽는다.
 *
 * common:outbox 이전 시: preorder outbox.publish.OutboxProperties 의 복사본이다. 그대로 공통으로 옮긴다.
 *
 * @param concurrency 커밋 직후 발행 동시 실행 상한
 * @param relayAfter  만든 지 이만큼 지난 미발행 행만 릴레이가 가져간다
 * @param relayBatch  릴레이 한 번에 잠가 보내는 최대 행 수
 */
@ConfigurationProperties("nova.outbox")
public record OutboxProperties(
        @DefaultValue("32") int concurrency,
        @DefaultValue("1m") Duration relayAfter,
        @DefaultValue("100") int relayBatch
) {

    public OutboxProperties {
        if (concurrency < 1 || relayBatch < 1) {
            throw new IllegalArgumentException("nova.outbox.concurrency · relay-batch 는 1 이상이어야 한다");
        }
        if (!relayAfter.isPositive()) {
            throw new IllegalArgumentException("nova.outbox.relay-after 는 0 보다 커야 한다");
        }
    }
}
