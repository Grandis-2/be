package com.grandis.nova.preorder.outbox.publish;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param concurrency 커밋 직후 발행 동시 실행 상한
 * @param relayAfter  만든 지 이만큼 지난 미발행 행만 릴레이가 가져간다
 * @param relayBatch  릴레이 한 번에 가져가 보내는 최대 행 수
 * @param relayLease  릴레이가 가져간 행을 다른 인스턴스가 건드리지 않는 시간. 한 묶음을 보내는 최대 시간
 *                    (relayBatch × 전송 제한 시간)보다 길게 둔다. 넘기면 남은 행은 보내지 않고 돌려놓는다
 */
@ConfigurationProperties("nova.outbox")
record OutboxProperties(
        @DefaultValue("32") int concurrency,
        @DefaultValue("1m") Duration relayAfter,
        @DefaultValue("100") int relayBatch,
        @DefaultValue("10m") Duration relayLease
) {

    public OutboxProperties {
        if (concurrency < 1 || relayBatch < 1) {
            throw new IllegalArgumentException("nova.outbox.concurrency · relay-batch 는 1 이상이어야 한다");
        }
        if (!relayAfter.isPositive() || !relayLease.isPositive()) {
            throw new IllegalArgumentException("nova.outbox.relay-after · relay-lease 는 0 보다 커야 한다");
        }
    }
}
