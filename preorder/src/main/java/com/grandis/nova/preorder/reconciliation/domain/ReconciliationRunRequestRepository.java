package com.grandis.nova.preorder.reconciliation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationRunRequestRepository extends JpaRepository<ReconciliationRunRequest, UUID> {
}
