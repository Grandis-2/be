package com.grandis.nova.preorder.syncjob.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.syncjob.SyncAttempt;
import com.grandis.nova.preorder.syncjob.SyncAttemptReader;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import com.grandis.nova.preorder.syncjob.application.ReprocessBatchStore.StoredBatch;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJobRepository;
import com.grandis.nova.preorder.syncjob.domain.ReprocessCandidate;
import com.grandis.nova.preorder.syncjob.domain.ReprocessCandidateReader;
import com.grandis.nova.preorder.syncjob.domain.SyncJobFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** 관리자 동기화 작업 조회 · 재처리. */
@Service
public class SyncJobAdminService {

    /** 일괄 재처리 한 번의 최대 건수. 후보를 모두 메모리에 올리지 않는다. */
    public static final int MAX_BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(SyncJobAdminService.class);
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final PreorderSyncJobRepository syncJobs;
    private final Preorders preorders;
    private final SyncAttemptReader syncAttempts;
    private final ReprocessCandidateReader candidateReader;
    private final SyncJobReprocessor reprocessor;
    private final TaskExecutor reprocessExecutor;
    private final ReprocessBatchStore batches;
    private final Clock clock;

    SyncJobAdminService(PreorderSyncJobRepository syncJobs, Preorders preorders,
                               SyncAttemptReader syncAttempts, ReprocessCandidateReader candidateReader,
                               SyncJobReprocessor reprocessor,
                               @Qualifier(SyncJobConfig.REPROCESS_EXECUTOR) TaskExecutor reprocessExecutor,
                               ReprocessBatchStore batches, Clock clock) {
        this.syncJobs = syncJobs;
        this.preorders = preorders;
        this.syncAttempts = syncAttempts;
        this.candidateReader = candidateReader;
        this.reprocessor = reprocessor;
        this.reprocessExecutor = reprocessExecutor;
        this.batches = batches;
        this.clock = clock;
    }

    /** 없는 예약 id 로 거르면 빈 목록이다. */
    @Transactional(readOnly = true)
    public SyncJobPage find(SyncJobType jobType, SyncJobStatus status, String preorderToken, boolean groupByError,
                            int page, int size) {
        Optional<SyncJobFilter> filter = filter(jobType, status, preorderToken);
        if (filter.isEmpty()) {
            return new SyncJobPage(OffsetPage.of(List.of(), page, size, 0), groupByError ? List.of() : null);
        }
        Page<PreorderSyncJob> jobs = syncJobs.findAll(filter.get().toSpecification(),
                PageRequest.of(page, size, NEWEST_FIRST));
        OffsetPage<SyncJobView> views = OffsetPage.of(views(jobs.getContent()), page, size, jobs.getTotalElements());
        return new SyncJobPage(views, groupByError ? syncAttempts.countByLastErrorCode(filter.get()) : null);
    }

    @Transactional(readOnly = true)
    public SyncJobView findOne(UUID syncJobId) {
        PreorderSyncJob job = syncJobs.findById(syncJobId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_FOUND));
        return views(List.of(job)).getFirst();
    }

    public SyncJobView reprocess(UUID syncJobId, String requestedBy) {
        return views(List.of(reprocessor.reprocess(syncJobId, requestedBy))).getFirst();
    }

    /**
     * 대상을 골라 바로 돌려주고, 이 인스턴스가 초당 ratePerSecond 건씩 재처리 요청을 기록한다(한 번에 최대 MAX_BATCH_SIZE).
     * syncJobIds 를 비우면 DEAD_LETTER 인 jobType 작업을 만든 순으로. 남은 것은 다시 요청하면 이어진다.
     * 재처리 조건 밖이거나 없는 작업은 건너뛴 수로 세고, errorCodeFilter 에 맞지 않는 작업은 세지 않는다.
     * 진행은 batchId 로 {@link #findBatch} 에서 본다.
     */
    public BatchReprocess reprocessBatch(List<UUID> syncJobIds, SyncJobType jobType, String errorCodeFilter,
                                         int ratePerSecond, String requestedBy) {
        List<ReprocessCandidate> candidates;
        int missing = 0;
        if (syncJobIds == null || syncJobIds.isEmpty()) {
            candidates = candidateReader.findDeadLetters(jobType, errorCodeFilter, MAX_BATCH_SIZE);
        } else {
            List<UUID> ids = syncJobIds.stream().distinct().toList();
            if (ids.size() > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("한 번에 " + MAX_BATCH_SIZE + " 건까지다: " + ids.size());
            }
            candidates = candidateReader.findByIds(ids);
            missing = ids.size() - candidates.size();
            if (errorCodeFilter != null) {
                candidates = candidates.stream().filter(c -> errorCodeFilter.equals(c.lastErrorCode())).toList();
            }
        }
        List<UUID> targets = candidates.stream()
                .filter(ReprocessCandidate::reprocessable)
                .map(ReprocessCandidate::syncJobId)
                .toList();
        int skipped = candidates.size() - targets.size() + missing;
        UUID batchId = UUID.randomUUID();
        batches.start(batchId, targets, skipped, clock.instant());
        if (!targets.isEmpty()) {
            reprocessExecutor.execute(() -> reprocessPaced(batchId, targets, ratePerSecond, requestedBy));
        }
        return new BatchReprocess(batchId, targets.size(), skipped,
                (targets.size() + ratePerSecond - 1L) / ratePerSecond);
    }

    /**
     * 일괄 재처리 진행. 결과 수는 다룬 대상 작업의 지금 상태로 센다(아직 다루지 않은 대상은 기다리는 수).
     *
     * @throws BusinessException REPROCESS_BATCH_NOT_FOUND — 없거나 보관 시간이 지났다
     */
    @Transactional(readOnly = true)
    public BatchReprocessProgress findBatch(UUID batchId) {
        StoredBatch stored = batches.find(batchId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.REPROCESS_BATCH_NOT_FOUND));
        List<UUID> attempted = stored.targets().subList(0, Math.min(stored.attempted(), stored.targets().size()));
        Map<SyncJobStatus, Long> byStatus = attempted.isEmpty() ? Map.of() : syncJobs.findAllById(attempted).stream()
                .collect(Collectors.groupingBy(PreorderSyncJob::getStatus, Collectors.counting()));
        int succeeded = byStatus.getOrDefault(SyncJobStatus.SUCCEEDED, 0L).intValue();
        int failed = byStatus.getOrDefault(SyncJobStatus.DEAD_LETTER, 0L).intValue();
        int canceled = byStatus.getOrDefault(SyncJobStatus.CANCELED, 0L).intValue();
        return new BatchReprocessProgress(batchId, stored.targets().size(), stored.skipped(), stored.sent(),
                succeeded, failed, canceled, stored.targets().size() - succeeded - failed - canceled,
                stored.startedAt(), stored.lastProgressAt(), stored.finishedAt());
    }

    private void reprocessPaced(UUID batchId, List<UUID> targets, int ratePerSecond, String requestedBy) {
        Duration interval = Duration.ofNanos(Duration.ofSeconds(1).toNanos() / ratePerSecond);
        for (UUID syncJobId : targets) {
            boolean sent = false;
            try {
                reprocessor.reprocess(syncJobId, requestedBy);
                sent = true;
            } catch (BusinessException e) {
                log.info("재처리 대상에서 빠졌다(그 사이 상태가 바뀜) syncJobId={} reason={}", syncJobId, e.details());
            } catch (RuntimeException e) {
                log.warn("재처리 요청을 남기지 못했다 — 같은 요청을 다시 보내면 이어진다 syncJobId={}", syncJobId, e);
            }
            batches.progress(batchId, sent, clock.instant());
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("일괄 재처리가 중단됐다 — 같은 요청을 다시 보내면 이어진다 batchId={}", batchId);
                return;
            }
        }
        batches.finish(batchId, clock.instant());
    }

    private Optional<SyncJobFilter> filter(SyncJobType jobType, SyncJobStatus status, String preorderToken) {
        if (preorderToken == null) {
            return Optional.of(new SyncJobFilter(jobType, status, null));
        }
        return preorders.findByToken(preorderToken)
                .map(preorder -> new SyncJobFilter(jobType, status, preorder.id()));
    }

    private List<SyncJobView> views(List<PreorderSyncJob> jobs) {
        Map<UUID, PreorderSnapshot> owners = preordersOf(jobs);
        Map<UUID, List<SyncAttempt>> attempts = syncAttempts.findByJobIds(ids(jobs));
        return jobs.stream()
                .map(job -> new SyncJobView(job, owners.get(job.getPreorderId()).preorderToken(),
                        attempts.getOrDefault(job.getId(), List.of())))
                .toList();
    }

    private Map<UUID, PreorderSnapshot> preordersOf(Collection<PreorderSyncJob> jobs) {
        return preorders.findAllById(jobs.stream().map(PreorderSyncJob::getPreorderId).distinct().toList());
    }

    private List<UUID> ids(Collection<PreorderSyncJob> jobs) {
        return jobs.stream().map(PreorderSyncJob::getId).toList();
    }
}
