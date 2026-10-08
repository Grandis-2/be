package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/** 목록 조회 조건. 값이 없는 조건은 아무것도 거르지 않는다(Specification.unrestricted). */
public final class PreorderSpecifications {

    private PreorderSpecifications() {
    }

    @SafeVarargs
    public static Specification<Preorder> allOf(Specification<Preorder>... specifications) {
        return Specification.allOf(specifications);
    }

    public static Specification<Preorder> customer(UUID customerId) {
        return customerId == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("customerId"), customerId);
    }

    public static Specification<Preorder> status(PreorderStatus status) {
        return status == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    public static Specification<Preorder> product(UUID productId) {
        return productId == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("productId"), productId);
    }

    /** 커서보다 뒤(더 오래된 것). 정렬이 created_at · id 내림차순이라 둘을 함께 본다. */
    public static Specification<Preorder> after(Instant createdAt, UUID id) {
        return createdAt == null ? Specification.unrestricted() : (root, query, builder) -> builder.or(
                builder.lessThan(root.get("createdAt"), createdAt),
                builder.and(builder.equal(root.get("createdAt"), createdAt), builder.lessThan(root.get("id"), id)));
    }
}
