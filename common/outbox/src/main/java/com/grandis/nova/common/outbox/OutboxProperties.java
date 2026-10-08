package com.grandis.nova.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * nova.outbox.transport(전송 구현 선택)는 여기 없다 — 구현 빈의 조건으로만 읽는다. 없으면 전송 구현이 없어 기동이 실패한다.
 *
 * @param concurrency   커밋 직후 발행 동시 실행 상한
 * @param relayAfter    만든 지 이만큼 지난 미발행 행만 릴레이가 가져간다
 * @param relayBatch    릴레이 한 번에 가져가 보내는 최대 행 수
 * @param relayLease    릴레이가 가져간 행을 다른 인스턴스가 건드리지 않는 시간. 행마다 보내기 직전에 연장하므로
 *                      전송 한 번의 제한 시간보다 길면 된다
 * @param relayInterval 릴레이 주기(앞 실행이 끝난 뒤부터 잰다). 미발행 현황 지표도 이 주기로 센다
 * @param metricsPrefix 지표 이름 접두어. 지표 레지스트리가 있는 서비스에서만 쓰인다
 * @param retention       발행 완료 행을 남겨 두는 기간. 지난 행은 정리 작업이 지운다(미발행 행은 지우지 않는다)
 * @param cleanupInterval 정리 주기(앞 실행이 끝난 뒤부터 잰다)
 * @param cleanupBatch    정리가 한 문장에 지우는 최대 행 수
 */
@ConfigurationProperties("nova.outbox")
record OutboxProperties(
        @DefaultValue("32") int concurrency,
        @DefaultValue("1m") Duration relayAfter,
        @DefaultValue("100") int relayBatch,
        @DefaultValue("1m") Duration relayLease,
        @DefaultValue("10s") Duration relayInterval,
        @DefaultValue("outbox") String metricsPrefix,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("1h") Duration cleanupInterval,
        @DefaultValue("1000") int cleanupBatch
) {

    OutboxProperties {
        if (concurrency < 1 || relayBatch < 1) {
            throw new IllegalArgumentException("nova.outbox.concurrency · relay-batch 는 1 이상이어야 한다");
        }
        if (!relayAfter.isPositive() || !relayLease.isPositive() || !relayInterval.isPositive()) {
            throw new IllegalArgumentException("nova.outbox.relay-after · relay-lease · relay-interval 은 0 보다 커야 한다");
        }
        if (!retention.isPositive() || !cleanupInterval.isPositive() || cleanupBatch < 1) {
            throw new IllegalArgumentException("nova.outbox.retention · cleanup-interval 은 0 보다 크고 cleanup-batch 는 1 이상이어야 한다");
        }
        if (metricsPrefix == null || metricsPrefix.isBlank()) {
            throw new IllegalArgumentException("nova.outbox.metrics-prefix 가 비어 있다");
        }
    }
}
