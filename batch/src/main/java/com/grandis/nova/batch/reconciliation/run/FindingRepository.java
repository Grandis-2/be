package com.grandis.nova.batch.reconciliation.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FindingRepository extends JpaRepository<ReconciliationFinding, UUID> {

    /** 처음 발견 시각을 잇는 데 쓰는 세 컬럼만 읽는다 — 스냅샷(JSON)까지 엔티티로 읽지 않는다. 실행 하나는 최대 1만 건이다 */
    @Query("""
            select new com.grandis.nova.batch.reconciliation.run.FirstDetected(f.checkCode, f.targetKey, f.firstDetectedAt)
              from ReconciliationFinding f
             where f.runId = :runId""")
    List<FirstDetected> findFirstDetected(@Param("runId") UUID runId);
}
