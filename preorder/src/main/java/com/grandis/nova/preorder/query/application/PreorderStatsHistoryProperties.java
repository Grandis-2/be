package com.grandis.nova.preorder.query.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param interval    예약 수를 기록하는 주기. 10초면 1분 속도를 표본 여섯 개로 재 한두 번 늦은 기록에 흔들리지 않는다. @Scheduled 가 같은 키를 읽는다
 * @param retention   기록을 두는 시간이자 지난 기록으로 물을 수 있는 최대 시간. 오픈 직후 30분이 밀림 판정에 쓰이는 구간이다
 * @param rateWindow  속도를 재는 구간. 1분이면 새로 고친 직후에도 바로 판정하고, 한 주기의 들쭉날쭉함은 고르게 편다
 * @param maxProducts 한 번에 기록하는 접수 중 상품 수(마감 임박순). 상품별 현황 한 페이지 상한과 맞춘다
 */
@ConfigurationProperties("nova.preorder-stats.history")
record PreorderStatsHistoryProperties(
        @DefaultValue("10s") Duration interval,
        @DefaultValue("30m") Duration retention,
        @DefaultValue("1m") Duration rateWindow,
        @DefaultValue("100") int maxProducts
) {
    PreorderStatsHistoryProperties {
        if (!interval.isPositive() || retention.compareTo(interval) < 0) {
            throw new IllegalArgumentException("nova.preorder-stats.history 의 interval 은 0 보다 크고 retention 이하여야 한다");
        }
        if (rateWindow.compareTo(interval) < 0 || rateWindow.compareTo(retention) > 0) {
            throw new IllegalArgumentException(
                    "nova.preorder-stats.history.rate-window 는 interval 이상 retention 이하여야 한다");
        }
        if (maxProducts < 0) {
            throw new IllegalArgumentException("nova.preorder-stats.history.max-products 는 0 이상이어야 한다");
        }
    }
}
