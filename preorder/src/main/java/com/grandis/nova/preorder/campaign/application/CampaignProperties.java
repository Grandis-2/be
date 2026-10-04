package com.grandis.nova.preorder.campaign.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param minLeadTime 회차를 만들거나 일정을 바꿀 때 오픈까지 남아야 하는 최소 시간.
 *                    커밋 직후 발행이 실패해 릴레이가 다시 보내도 오픈 전에 대기열이 일정을 받게 한다
 */
@ConfigurationProperties("nova.campaign")
record CampaignProperties(
        @DefaultValue("10m") Duration minLeadTime
) {
    CampaignProperties {
        if (minLeadTime.isNegative()) {
            throw new IllegalArgumentException("nova.campaign.min-lead-time 은 0 이상이어야 한다");
        }
    }
}
