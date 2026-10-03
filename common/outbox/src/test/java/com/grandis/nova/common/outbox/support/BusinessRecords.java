package com.grandis.nova.common.outbox.support;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRecords extends JpaRepository<BusinessRecord, Long> {
}
