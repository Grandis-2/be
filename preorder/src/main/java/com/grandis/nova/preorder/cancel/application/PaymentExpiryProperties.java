package com.grandis.nova.preorder.cancel.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 결제 기한 만료 주기 작업. 꺼져 있어도 바인딩해 값을 검사한다 — 잘못된 값이 켜는 날 처음 드러나지 않게.
 *
 * @param enabled   주기 작업을 켠다. 주문 쪽이 결제 확인을 보내기 전에 켜면 결제된 예약이 만료 → 거절로 막힌다
 * @param interval  기한이 지난 예약을 찾는 주기(10초 ~ 1시간). 늦어도 기한 뒤 결제는 결제 가능 검사가 막아 정리만 늦는다.
 *                  @Scheduled 가 같은 키를 읽는다
 * @param batchSize 한 주기에 집는 예약 수(1 ~ 1000). 건마다 따로 커밋하며, 남으면 다음 주기에 이어 간다
 */
@ConfigurationProperties("nova.preorder-expiry")
record PaymentExpiryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1m") Duration interval,
        @DefaultValue("100") int batchSize
) {

    static final Duration MIN_INTERVAL = Duration.ofSeconds(10);
    static final Duration MAX_INTERVAL = Duration.ofHours(1);
    static final int MAX_BATCH_SIZE = 1000;

    PaymentExpiryProperties {
        if (interval.compareTo(MIN_INTERVAL) < 0 || interval.compareTo(MAX_INTERVAL) > 0) {
            throw new IllegalArgumentException("nova.preorder-expiry.interval 은 10초 ~ 1시간이어야 한다: " + interval);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("nova.preorder-expiry.batch-size 는 1 ~ 1000 이어야 한다: " + batchSize);
        }
    }
}
