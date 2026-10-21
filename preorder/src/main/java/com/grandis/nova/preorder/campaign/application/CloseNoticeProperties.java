package com.grandis.nova.preorder.campaign.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 마감 알림 주기 작업.
 *
 * @param enabled   보내기를 켠다. 기본은 꺼짐 — catalog 는 처리기가 없는 종류를 재시도 뒤 DLQ 로 보내므로 catalog 처리기가 배포된 뒤 켠다.
 *                  꺼진 동안의 마감은 표시도 하지 않아, 켠 뒤에는 창 안의 마감만 나간다
 * @param interval  회차를 찾는 주기. 알림이 마감보다 이만큼까지 늦을 수 있다. @Scheduled 가 같은 키를 읽는다
 * @param window    마감한 지 이 시간이 넘은 회차는 보내지 않는다 — 첫 배포 · 긴 장애 뒤에 지난 마감이 몰려 나가지 않게
 * @param batchSize 한 번에 집는 마감 수. 남으면 다음 주기에 이어 간다
 */
@ConfigurationProperties("nova.campaign.close-notice")
record CloseNoticeProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("1h") Duration window,
        @DefaultValue("100") int batchSize
) {
    CloseNoticeProperties {
        if (!interval.isPositive() || !window.isPositive() || batchSize <= 0) {
            throw new IllegalArgumentException("nova.campaign.close-notice 의 interval · window · batch-size 는 0 보다 커야 한다");
        }
    }
}
