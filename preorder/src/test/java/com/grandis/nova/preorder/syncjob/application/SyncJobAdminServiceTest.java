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
import java.util.Set;
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

    /** 보내기 전 · 못 보낸 대상의 지금 상태는 이번 재처리의 결과가 아니다 — 포기 · 성공 상태 그대로라 결과로 세면 잘못 읽힌다. */
    @Test
    void 결과는_요청을_남긴_작업만_세고_못_남긴_작업과_아직_다루지_않은_대상은_뺀다() {
        UUID batchId = UUID.randomUUID();
        UUID sentAndFailed = UUID.randomUUID();
        UUID unsent = UUID.randomUUID();
        UUID notYet = UUID.randomUUID();
        Instant startedAt = Instant.parse("2026-10-24T00:59:00Z");
        given(batches.find(batchId)).willReturn(Optional.of(new StoredBatch(List.of(sentAndFailed, unsent, notYet),
                0, startedAt, 2, Set.of(sentAndFailed), startedAt, null)));
        PreorderSyncJob failedAgain = job(SyncJobStatus.DEAD_LETTER);
        given(syncJobs.findAllById(Set.of(sentAndFailed))).willReturn(List.of(failedAgain));

        BatchReprocessProgress progress = service.findBatch(batchId);

        assertThat(progress.targetCount()).isEqualTo(3);
        assertThat(progress.sent()).isEqualTo(1);
        assertThat(progress.unsent()).isEqualTo(1);
        assertThat(progress.failed()).isEqualTo(1);
        assertThat(progress.succeeded()).isZero();
        assertThat(progress.pending()).isEqualTo(1);
        assertThat(progress.finishedAt()).isNull();
    }

    private static PreorderSyncJob job(SyncJobStatus status) {
        PreorderSyncJob job = mock(PreorderSyncJob.class);
        given(job.getStatus()).willReturn(status);
        return job;
    }
}
