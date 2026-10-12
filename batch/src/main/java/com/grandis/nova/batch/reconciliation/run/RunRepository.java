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

    /** batch 가 다시 떴을 때 남아 있는 대기 · 실행 중을 실패로 끝낸다. 일괄 UPDATE 라 변경 시각을 직접 적는다 */
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
