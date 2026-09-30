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
import java.util.stream.Collectors;

/**
 * 외부 동기화 작업(모듈 공개 API). 작업을 만들고 무효화하는 일은 예약 변경 · 아웃박스와 한 트랜잭션이어야 해서
 * 호출한 쪽의 트랜잭션에서만 한다(MANDATORY). 값은 스냅샷으로만 내준다.
 */
@Service
public class SyncJobs {

    private final PreorderSyncJobRepository syncJobs;

    SyncJobs(PreorderSyncJobRepository syncJobs) {
        this.syncJobs = syncJobs;
    }

    /** 외부 등록 작업을 만든다. @return 작업 id */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long createRegister(Long preorderId, String requestPayload) {
        return syncJobs.save(PreorderSyncJob.register(preorderId, requestPayload)).getId();
    }

    /** 외부 취소 작업을 만든다. @return 작업 id */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long createCancel(Long preorderId, String requestPayload) {
        return syncJobs.save(PreorderSyncJob.cancel(preorderId, requestPayload)).getId();
    }

    /** 아직 성공하지 않은 등록 작업을 무효화한다(취소 시작). @return 바뀐 행 수 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelRegister(Long preorderId, Instant now) {
        return syncJobs.cancelRegister(preorderId, now);
    }

    @Transactional(readOnly = true)
    public Optional<SyncJobSnapshot> findById(Long syncJobId) {
        return syncJobs.findById(syncJobId).map(SyncJobSnapshot::of);
    }

    @Transactional(readOnly = true)
    public boolean exists(Long preorderId, SyncJobType jobType) {
        return syncJobs.findByPreorderIdAndJobType(preorderId, jobType).isPresent();
    }

    /** 예약의 작업(종류 순). */
    @Transactional(readOnly = true)
    public List<SyncJobSnapshot> findByPreorder(Long preorderId) {
        return syncJobs.findByPreorderIdOrderByJobType(preorderId).stream().map(SyncJobSnapshot::of).toList();
    }

    /** 예약 id → 등록 작업 상태. 작업이 없는 예약은 빠진다. */
    @Transactional(readOnly = true)
    public Map<Long, SyncJobStatus> registerStatuses(Collection<Long> preorderIds) {
        if (preorderIds.isEmpty()) {
            return Map.of();
        }
        return syncJobs.findByPreorderIdInAndJobType(preorderIds, SyncJobType.REGISTER).stream()
                .collect(Collectors.toMap(PreorderSyncJob::getPreorderId, PreorderSyncJob::getStatus));
    }
}
