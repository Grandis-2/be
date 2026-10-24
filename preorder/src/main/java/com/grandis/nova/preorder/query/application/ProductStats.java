package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.deadletter.DeadLetterWaiting;
import com.grandis.nova.preorder.preorder.PreorderBacklog;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;

import java.util.Map;

/**
 * 상품 하나의 운영 현황. {@link PreorderStats} 와 같은 숫자에 회차 일정이 붙는다.
 *
 * @param syncJobs 끝나지 않은 작업만(종류 → 상태 → 수)
 */
public record ProductStats(
        CampaignSchedule campaign,
        PreorderBacklog preorders,
        Map<SyncJobType, Map<SyncJobStatus, Long>> syncJobs,
        DeadLetterWaiting deadLetters
) {
}
