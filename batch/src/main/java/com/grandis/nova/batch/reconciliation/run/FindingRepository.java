package com.grandis.nova.batch.reconciliation.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface FindingRepository extends JpaRepository<ReconciliationFinding, UUID> {

    List<ReconciliationFinding> findAllByRunId(UUID runId);
}
