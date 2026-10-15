package com.grandis.nova.preorder.campaign.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 오픈 알림 주기 작업.
 *
 * @param interval  회차를 찾는 주기. 알림이 오픈보다 이만큼까지 늦을 수 있다. @Scheduled 가 같은 키를 읽는다
 * @param window    연 지 이 시간이 넘은 회차는 보내지 않는다 — 첫 배포 · 긴 장애 뒤에 지난 알림이 몰려 나가지 않게. 이어 보내던 발송도 그만둔다
 * @param batchSize 한 번에 집는 회차 수(창 안의 보낼 회차와 끝나지 않은 발송을 각각 센다). 남으면 다음 주기에 이어 간다
 */
@ConfigurationProperties("nova.campaign.open-alert")
record OpenAlertProperties(
        @DefaultValue("30s") Duration interval,
        @DefaultValue("1h") Duration window,
        @DefaultValue("100") int batchSize
) {
    OpenAlertProperties {
        if (!interval.isPositive() || !window.isPositive() || batchSize <= 0) {
            throw new IllegalArgumentException("nova.campaign.open-alert 의 interval · window · batch-size 는 0 보다 커야 한다");
        }
    }
}
