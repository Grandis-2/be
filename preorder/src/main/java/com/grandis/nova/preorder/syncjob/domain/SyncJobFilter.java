package com.grandis.nova.preorder.syncjob.domain;

import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import org.springframework.data.jpa.domain.Specification;

/** 관리자 작업 목록 조건. 비운 칸은 거르지 않는다. preorderId 는 예약 내부 id 다. */
public record SyncJobFilter(SyncJobType jobType, SyncJobStatus status, Long preorderId) {

    public Specification<PreorderSyncJob> toSpecification() {
        return Specification.allOf(equalTo("jobType", jobType), equalTo("status", status),
                equalTo("preorderId", preorderId));
    }

    private Specification<PreorderSyncJob> equalTo(String attribute, Object value) {
        return value == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get(attribute), value);
    }
}
