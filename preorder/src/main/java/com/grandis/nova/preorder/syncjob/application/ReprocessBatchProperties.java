package com.grandis.nova.preorder.syncjob.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param progressTtl 일괄 재처리 진행을 Redis 에 두는 시간. 1000 건을 초당 1 건으로 보내도 17분이면 끝나고, 결과는 worker 가
 *                    재시도까지 마친 뒤에야 나므로 하루면 다음 날 출근해 확인하기까지 남는다
 */
@ConfigurationProperties("nova.sync-job.reprocess-batch")
record ReprocessBatchProperties(
        @DefaultValue("24h") Duration progressTtl
) {
    ReprocessBatchProperties {
        if (!progressTtl.isPositive()) {
            throw new IllegalArgumentException("nova.sync-job.reprocess-batch.progress-ttl 은 0 보다 커야 한다");
        }
    }
}
