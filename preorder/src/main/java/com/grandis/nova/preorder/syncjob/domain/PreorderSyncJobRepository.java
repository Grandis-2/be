package com.grandis.nova.preorder.syncjob.domain;

import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PreorderSyncJobRepository extends JpaRepository<PreorderSyncJob, Long>,
        JpaSpecificationExecutor<PreorderSyncJob> {

    /** 끝난 REGISTER 작업. 무효화하지 않는다. */
    List<SyncJobStatus> REGISTER_FINISHED = List.of(SyncJobStatus.SUCCEEDED, SyncJobStatus.CANCELED);

    Optional<PreorderSyncJob> findByPreorderIdAndJobType(Long preorderId, SyncJobType jobType);

    /** 예약 하나의 작업(REGISTER · CANCEL 최대 1건씩). 관리자 상세에서 쓴다. */
    List<PreorderSyncJob> findByPreorderIdOrderByJobType(Long preorderId);

    /** 목록 화면용. 여러 예약의 작업을 한 번에 읽는다(행마다 다시 묻지 않는다). */
    List<PreorderSyncJob> findByPreorderIdInAndJobType(Collection<Long> preorderIds, SyncJobType jobType);

    /**
     * 예약 취소 시작 트랜잭션에서 아직 성공하지 않은 REGISTER 작업을 무효화한다(DEAD_LETTER 포함).
     * 실행 중인 worker 는 리스 토큰 조건부 완료가 0행이 되어 결과를 반영하지 못한다.
     * 이미 SUCCEEDED 면 0 — 외부에 등록된 것이므로 CANCEL 작업으로 취소한다.
     */
    default int cancelRegister(Long preorderId, Instant now) {
        return cancelUnfinished(preorderId, SyncJobType.REGISTER, REGISTER_FINISHED, SyncJobStatus.CANCELED, now);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PreorderSyncJob j
               set j.status = :canceled, j.updatedAt = :now
             where j.preorderId = :preorderId and j.jobType = :jobType and j.status not in :finished
            """)
    int cancelUnfinished(@Param("preorderId") Long preorderId, @Param("jobType") SyncJobType jobType,
                         @Param("finished") Collection<SyncJobStatus> finished,
                         @Param("canceled") SyncJobStatus canceled, @Param("now") Instant now);

    /** 작업의 예약 id 만 읽는다. 예약을 먼저 잠그려고 작업 엔티티를 올리지 않는다. */
    @Query("select j.preorderId from PreorderSyncJob j where j.id = :id")
    Optional<Long> findPreorderId(@Param("id") Long id);
}
