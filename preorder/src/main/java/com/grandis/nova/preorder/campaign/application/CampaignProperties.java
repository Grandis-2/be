package com.grandis.nova.preorder.campaign.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param minLeadTime       회차를 만들거나 일정을 바꿀 때 오픈까지 남아야 하는 최소 시간.
 *                          커밋 직후 발행이 실패해 릴레이가 다시 보내도 오픈 전에 대기열이 일정을 받게 한다
 * @param internalLookahead 접수 중 회차 내부 API 가 미리 주는 "곧 열릴" 구간. catalog 캐시(약 5초)보다 훨씬 길어
 *                          캐시가 낡아도 곧 열릴 회차를 놓치지 않는다 — 길면 응답만 커진다
 * @param internalOpenLimit 접수 중 회차 내부 API 의 응답 상한. 실제 규모는 수십 건이고, catalog 의 IN 조건이 한없이 커지지 않게 막는다
 */
@ConfigurationProperties("nova.campaign")
record CampaignProperties(
        @DefaultValue("10m") Duration minLeadTime,
        @DefaultValue("10m") Duration internalLookahead,
        @DefaultValue("500") int internalOpenLimit
) {

    static final int MAX_INTERNAL_OPEN_LIMIT = 10_000;

    CampaignProperties {
        if (minLeadTime.isNegative()) {
            throw new IllegalArgumentException("nova.campaign.min-lead-time 은 0 이상이어야 한다");
        }
        if (internalLookahead.isNegative()) {
            throw new IllegalArgumentException("nova.campaign.internal-lookahead 는 0 이상이어야 한다");
        }
        // 위쪽 한계는 응답 · catalog 의 IN 조건이 감당할 크기다(실제 규모는 수십 건). 조회가 상한 + 1 건을 읽으므로 넘침도 막는다
        if (internalOpenLimit < 1 || internalOpenLimit > MAX_INTERNAL_OPEN_LIMIT) {
            throw new IllegalArgumentException(
                    "nova.campaign.internal-open-limit 은 1~" + MAX_INTERNAL_OPEN_LIMIT + " 이어야 한다");
        }
    }
}
