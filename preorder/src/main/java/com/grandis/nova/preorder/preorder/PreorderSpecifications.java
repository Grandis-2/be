package com.grandis.nova.preorder.preorder;

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

    /** 커서보다 뒤(더 오래된 것). 정렬이 created_at · id 내림차순이라 둘을 함께 본다. */
    public static Specification<Preorder> after(Instant createdAt, Long id) {
        return createdAt == null ? Specification.unrestricted() : (root, query, builder) -> builder.or(
                builder.lessThan(root.get("createdAt"), createdAt),
                builder.and(builder.equal(root.get("createdAt"), createdAt), builder.lessThan(root.get("id"), id)));
    }
}
