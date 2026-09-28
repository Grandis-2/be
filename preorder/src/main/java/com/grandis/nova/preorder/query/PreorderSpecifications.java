package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.syncjob.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/** 목록 조회 조건. 값이 없는 조건은 아무것도 거르지 않는다(Specification.unrestricted). */
final class PreorderSpecifications {

    private PreorderSpecifications() {
    }

    @SafeVarargs
    public static Specification<Preorder> allOf(Specification<Preorder>... specifications) {
        return Specification.allOf(specifications);
    }

    public static Specification<Preorder> customer(Long customerId) {
        return customerId == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("customerId"), customerId);
    }

    public static Specification<Preorder> status(PreorderStatus status) {
        return status == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    public static Specification<Preorder> product(Long productId) {
        return productId == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("productId"), productId);
    }

    public static Specification<Preorder> createdFrom(Instant from) {
        return from == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<Preorder> createdUntil(Instant to) {
        return to == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.lessThan(root.get("createdAt"), to);
    }

    /** 커서보다 뒤(더 오래된 것). 정렬이 created_at · id 내림차순이라 둘을 함께 본다. */
    public static Specification<Preorder> after(Instant createdAt, Long id) {
        return createdAt == null ? Specification.unrestricted() : (root, query, builder) -> builder.or(
                builder.lessThan(root.get("createdAt"), createdAt),
                builder.and(builder.equal(root.get("createdAt"), createdAt), builder.lessThan(root.get("id"), id)));
    }

    /** 외부 등록(REGISTER) 작업이 그 상태인 예약. 관리자 목록에서 DLQ 대기 건을 거를 때 쓴다. */
    public static Specification<Preorder> registerJobStatus(SyncJobStatus jobStatus) {
        return jobStatus == null ? Specification.unrestricted() : (root, query, builder) -> {
            Subquery<Long> jobs = query.subquery(Long.class);
            var job = jobs.from(PreorderSyncJob.class);
            jobs.select(job.get("preorderId")).where(
                    builder.equal(job.get("preorderId"), root.get("id")),
                    builder.equal(job.get("jobType"), SyncJobType.REGISTER),
                    builder.equal(job.get("status"), jobStatus));
            return builder.exists(jobs);
        };
    }
}
