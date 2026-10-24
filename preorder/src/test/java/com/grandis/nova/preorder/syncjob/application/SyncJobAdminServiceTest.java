package com.grandis.nova.preorder.syncjob.application;

import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.syncjob.SyncAttemptReader;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.application.ReprocessBatchStore.StoredBatch;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJobRepository;
import com.grandis.nova.preorder.syncjob.domain.ReprocessCandidateReader;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SyncJobAdminServiceTest {

    final PreorderSyncJobRepository syncJobs = mock(PreorderSyncJobRepository.class);
    final ReprocessBatchStore batches = mock(ReprocessBatchStore.class);
    final SyncJobAdminService service = new SyncJobAdminService(syncJobs, mock(Preorders.class),
            mock(SyncAttemptReader.class), mock(ReprocessCandidateReader.class), mock(SyncJobReprocessor.class),
            new SyncTaskExecutor(), batches, Clock.fixed(Instant.parse("2026-10-24T01:00:00Z"), ZoneOffset.UTC));

    /** 보내기 전의 대상은 아직 포기 상태 그대로라 다시 포기한 것과 가르지 않으면 실패로 잘못 센다. */
    @Test
    void 아직_다루지_않은_대상은_지금_상태와_상관없이_기다리는_수로_센다() {
        UUID batchId = UUID.randomUUID();
        UUID sentAndFailed = UUID.randomUUID();
        UUID notYet = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-10-24T00:59:00Z");
        given(batches.find(batchId)).willReturn(Optional.of(new StoredBatch(List.of(sentAndFailed, notYet), 0,
                startedAt, 1, 1, startedAt, null)));
        PreorderSyncJob failedAgain = job(SyncJobStatus.DEAD_LETTER);
        given(syncJobs.findAllById(List.of(sentAndFailed))).willReturn(List.of(failedAgain));

        BatchReprocessProgress progress = service.findBatch(batchId);

        assertThat(progress.targetCount()).isEqualTo(2);
        assertThat(progress.sent()).isEqualTo(1);
        assertThat(progress.failed()).isEqualTo(1);
        assertThat(progress.pending()).isEqualTo(1);
        assertThat(progress.finishedAt()).isNull();
    }

    private static PreorderSyncJob job(SyncJobStatus status) {
        PreorderSyncJob job = mock(PreorderSyncJob.class);
        given(job.getStatus()).willReturn(status);
        return job;
    }
}
