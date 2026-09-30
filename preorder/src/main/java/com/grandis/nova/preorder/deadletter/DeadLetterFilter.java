package com.grandis.nova.preorder.deadletter;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/** 관리자 목록 조건. 비운 칸은 거르지 않는다. preorderId 는 예약 내부 id, 기간은 쌓인 시각 [from, to). */
record DeadLetterFilter(DeadLetterStatus status, String eventType, FailureReason failureReason, Long preorderId,
                        Long customerId, Instant from, Instant to) {

    public Specification<DeadLetterEvent> toSpecification() {
        return Specification.allOf(equalTo("status", status), equalTo("eventType", eventType),
                equalTo("failureReason", failureReason), equalTo("preorderId", preorderId),
                equalTo("customerId", customerId), createdFrom(), createdBefore());
    }

    private Specification<DeadLetterEvent> createdFrom() {
        return from == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    private Specification<DeadLetterEvent> createdBefore() {
        return to == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.lessThan(root.get("createdAt"), to);
    }

    private static Specification<DeadLetterEvent> equalTo(String attribute, Object value) {
        return value == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get(attribute), value);
    }
}
