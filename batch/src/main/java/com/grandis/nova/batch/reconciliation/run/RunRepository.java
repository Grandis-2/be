package com.grandis.nova.batch.reconciliation.run;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 실행 순서는 created_at 으로 본다 — id 는 UUID v7 이지만 같은 밀리초 안의 순서가 보장되지 않는다(BaseEntity). */
interface RunRepository extends JpaRepository<ReconciliationRun, UUID> {

    Optional<ReconciliationRun> findFirstByStatusOrderByCreatedAtAsc(RunStatus status);

    boolean existsByStatusIn(Collection<RunStatus> statuses);

    boolean existsByStartedAtGreaterThanEqual(Instant at);

    /** 이 실행보다 먼저 끝난, 범위가 같은 완료 실행. 처음 발견 시각을 이어받는 데 쓴다 */
    @Query("""
            select r from ReconciliationRun r
             where r.status = com.grandis.nova.batch.reconciliation.run.RunStatus.COMPLETED
               and r.createdAt < :before
               and ((:productId is null and r.productId is null) or r.productId = :productId)
             order by r.createdAt desc
            """)
    List<ReconciliationRun> findPreviousCompleted(@Param("before") Instant before, @Param("productId") UUID productId,
                                                  Limit limit);

    /*
     * 상태 전이는 모두 "지금 상태가 이것일 때만" 바꾸는 조건부 UPDATE 다. 영향 행 수가 0 이면 다른 곳(겹쳐 뜬 인스턴스 · 기동 정리)이
     * 먼저 바꾼 것이다. 엔티티를 읽고 고쳐 쓰면 그 사이에 바뀐 것을 덮는다. 일괄 UPDATE 라 변경 시각을 직접 적는다.
     */

    /** 요청(REQUESTED)을 가져가 실행 중으로 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReconciliationRun r
               set r.status = com.grandis.nova.batch.reconciliation.run.RunStatus.RUNNING,
                   r.startedAt = :now, r.cutoffAt = :cutoff, r.updatedAt = :now
             where r.id = :id and r.status = com.grandis.nova.batch.reconciliation.run.RunStatus.REQUESTED
            """)
    int claim(@Param("id") UUID id, @Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /** 실행 중(RUNNING)을 완료로 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReconciliationRun r
               set r.status = com.grandis.nova.batch.reconciliation.run.RunStatus.COMPLETED,
                   r.finishedAt = :now, r.heldCount = :held, r.pendingSyncCount = :pendingSync,
                   r.cancelingCount = :canceling, r.deadLetterCount = :deadLetter, r.updatedAt = :now
             where r.id = :id and r.status = com.grandis.nova.batch.reconciliation.run.RunStatus.RUNNING
            """)
    int complete(@Param("id") UUID id, @Param("now") Instant now, @Param("held") int held,
                 @Param("pendingSync") int pendingSync, @Param("canceling") int canceling,
                 @Param("deadLetter") int deadLetter);

    /** 대기 · 실행 중을 실패로 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReconciliationRun r
               set r.status = com.grandis.nova.batch.reconciliation.run.RunStatus.FAILED,
                   r.finishedAt = :now, r.errorMessage = :message, r.updatedAt = :now
             where r.id = :id
               and r.status in (com.grandis.nova.batch.reconciliation.run.RunStatus.REQUESTED,
                                com.grandis.nova.batch.reconciliation.run.RunStatus.RUNNING)
            """)
    int fail(@Param("id") UUID id, @Param("now") Instant now, @Param("message") String message);

    /** batch 가 다시 떴을 때 남아 있는 대기 · 실행 중을 모두 실패로 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReconciliationRun r
               set r.status = com.grandis.nova.batch.reconciliation.run.RunStatus.FAILED,
                   r.finishedAt = :now, r.errorMessage = :message, r.updatedAt = :now
             where r.status in (com.grandis.nova.batch.reconciliation.run.RunStatus.REQUESTED,
                                com.grandis.nova.batch.reconciliation.run.RunStatus.RUNNING)
            """)
    int failUnfinished(@Param("now") Instant now, @Param("message") String message);
}
