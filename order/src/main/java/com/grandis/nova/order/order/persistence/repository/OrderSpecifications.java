package com.grandis.nova.order.order.persistence.repository;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.OrderPosition;
import com.grandis.nova.order.order.persistence.entity.OrderJpaEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.Objects;

/** 목록 조회 조건. 값이 없는 조건은 아무것도 거르지 않는다(Specification.unrestricted). */
public final class OrderSpecifications {

    /** 최신순. 같은 시각이면 id 로 가른다 — 커서가 (created_at, id) 라 정렬도 둘이어야 한다. */
    public static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private OrderSpecifications() {
    }

    @SafeVarargs
    public static Specification<OrderJpaEntity> allOf(Specification<OrderJpaEntity>... specifications) {
        return Specification.allOf(specifications);
    }

    /**
     * 그 회원의 주문만. 다른 조건과 달리 null 을 "조건 없음" 으로 받지 않는다 — 권한 범위를 정하는 조건이라
     * 회원 id 가 빠지면 전 회원의 주문이 나간다. 닫힌 쪽으로 실패한다.
     */
    public static Specification<OrderJpaEntity> customer(Long customerId) {
        Objects.requireNonNull(customerId, "customerId");
        return (root, query, builder) -> builder.equal(root.get("customerId"), customerId);
    }

    public static Specification<OrderJpaEntity> status(OrderStatus status) {
        return status == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    public static Specification<OrderJpaEntity> source(OrderSource source) {
        return source == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("source"), source);
    }

    /** 커서보다 뒤(더 오래된 것). 정렬이 created_at · id 내림차순이라 둘을 함께 본다. */
    public static Specification<OrderJpaEntity> after(OrderPosition position) {
        return position == null ? Specification.unrestricted() : (root, query, builder) -> builder.or(
                builder.lessThan(root.get("createdAt"), position.createdAt()),
                builder.and(builder.equal(root.get("createdAt"), position.createdAt()),
                        builder.lessThan(root.get("id"), position.id())));
    }
}
