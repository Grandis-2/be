package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.domain.PreorderSyncJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 외부 동기화 작업(모듈 공개 API). 작업을 만들고 바꾸는 일은 예약 변경 · 아웃박스와 한 트랜잭션이어야 해서
 * 호출한 쪽의 트랜잭션에서만 한다(MANDATORY). 값은 스냅샷으로만 내준다.
 *
 * 작업 상태를 바꾸거나 시도를 남기기 전에 호출한 쪽이 예약 행을 잠근다 — 취소 · 재처리와 순서가 맞고 시도 번호가 겹치지 않는다.
 */
@Service
public class SyncJobs {

    private final PreorderSyncJobRepository syncJobs;
    private final SyncAttemptWriter attempts;

    SyncJobs(PreorderSyncJobRepository syncJobs, SyncAttemptWriter attempts) {
        this.syncJobs = syncJobs;
        this.attempts = attempts;
    }

    /** 외부 등록 작업을 만든다. @return 작업 id */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createRegister(UUID preorderId, String requestPayload) {
        return syncJobs.save(PreorderSyncJob.register(preorderId, requestPayload)).getId();
    }

    /** 외부 취소 작업을 만든다. @return 작업 id */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createCancel(UUID preorderId, String requestPayload) {
        return syncJobs.save(PreorderSyncJob.cancel(preorderId, requestPayload)).getId();
    }

    /** 아직 성공하지 않은 등록 작업을 무효화한다(취소 시작). @return 바뀐 행 수 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelRegister(UUID preorderId, Instant now) {
        return syncJobs.cancelRegister(preorderId, now);
    }

    /** 외부 호출이 성공했다. @return 바뀐 행 수(무효화된 작업이면 0) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int succeed(UUID syncJobId, Instant now) {
        return syncJobs.markSucceeded(syncJobId, now);
    }

    /** 실패한 차례가 있었고 worker 가 다시 보낸다. @return 바뀐 행 수(끝난 작업이면 0) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int scheduleRetry(UUID syncJobId, Instant now) {
        return syncJobs.markRetryScheduled(syncJobId, now);
    }

    /** worker 가 등록을 포기했다. 등록 작업에만 쓴다. @return 바뀐 행 수(끝난 작업이면 0) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deadLetter(UUID syncJobId, Instant now) {
        return syncJobs.markDeadLetter(syncJobId, now);
    }

    /** 결과 이벤트에 실려 온 외부 호출 한 번을 남긴다. @return 새로 남겼으면 true, 이미 받은 이벤트면 false */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordAttempt(UUID syncJobId, String eventId, NewSyncAttempt attempt) {
        return attempts.write(syncJobId, eventId, attempt);
    }

    /** 작업의 예약 id. 예약을 먼저 잠그려고 작업보다 앞서 읽는다. */
    @Transactional(readOnly = true)
    public Optional<UUID> findPreorderId(UUID syncJobId) {
        return syncJobs.findPreorderId(syncJobId);
    }

    @Transactional(readOnly = true)
    public Optional<SyncJobSnapshot> findById(UUID syncJobId) {
        return syncJobs.findById(syncJobId).map(PreorderSyncJob::toSnapshot);
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID preorderId, SyncJobType jobType) {
        return syncJobs.findByPreorderIdAndJobType(preorderId, jobType).isPresent();
    }

    /** 예약의 작업(종류 순). */
    @Transactional(readOnly = true)
    public List<SyncJobSnapshot> findByPreorder(UUID preorderId) {
        return syncJobs.findByPreorderIdOrderByJobType(preorderId).stream().map(PreorderSyncJob::toSnapshot).toList();
    }

    /** 예약 id → 등록 작업 상태. 작업이 없는 예약은 빠진다. */
    @Transactional(readOnly = true)
    public Map<UUID, SyncJobStatus> registerStatuses(Collection<UUID> preorderIds) {
        if (preorderIds.isEmpty()) {
            return Map.of();
        }
        return syncJobs.findByPreorderIdInAndJobType(preorderIds, SyncJobType.REGISTER).stream()
                .collect(Collectors.toMap(PreorderSyncJob::getPreorderId, PreorderSyncJob::getStatus));
    }
}
