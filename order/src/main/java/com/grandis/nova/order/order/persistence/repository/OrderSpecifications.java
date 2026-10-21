package com.grandis.nova.order.order.persistence.repository;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.OrderIdPrefix;
import com.grandis.nova.order.order.domain.repository.OrderPosition;
import com.grandis.nova.order.order.persistence.entity.OrderItemJpaEntity;
import com.grandis.nova.order.order.persistence.entity.OrderJpaEntity;
import com.grandis.nova.order.order.persistence.entity.OrderPhoneLast4JpaEntity;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 목록 조회 조건. 값이 없는 조건은 아무것도 거르지 않는다(Specification.unrestricted). */
public final class OrderSpecifications {

    /** 최신순. 같은 시각이면 id 로 가른다 — 커서가 (created_at, id) 라 정렬도 둘이어야 한다. */
    public static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    /**
     * 좁히는 조건({@link com.grandis.nova.order.order.domain.repository.AdminOrderFilter#narrowing()})이 있을 때의 최신순. 같은 시각(마이크로초) 안에서만 id 를 오름차순으로 둔다 — 그
     * 동률에서만 {@link #NEWEST_FIRST} 와 반대이고, 여전히 전순서라 쪽 넘김은 흔들리지 않는다.
     *
     * 왜: {@link #NEWEST_FIRST} 는 ix_order_created(created_at, 끝에 id)를 거꾸로 읽어 정렬 없이 줄 수 있어, LIMIT 이 있으면 MySQL 이 "그 색인을
     * 최신부터 훑으며 거르면 곧 한 쪽이 찬다" 고 어림해 조건의 색인 대신 고를 수 있다. 어림은 조건이 시각과 무관하다고 보지만 주문 번호 앞부분은
     * 시각 그 자체고, 상품 · 예약도 출시 · 접수 때에 몰린다 — 오래된 것을 찾으면 그보다 새 주문을 모두 훑는다. 방향이 섞인 정렬은 어떤 색인으로도
     * 줄 수 없어 그 계획이 생기지 않고, 조건의 색인으로 고른 행만 정렬한다(좁힌 행이라 적다).
     */
    public static final Sort NEWEST_FIRST_FILTERED =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));

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
    public static Specification<OrderJpaEntity> customer(UUID customerId) {
        Objects.requireNonNull(customerId, "customerId");
        return (root, query, builder) -> builder.equal(root.get("customerId"), customerId);
    }

    /** 관리자 조건 — {@link #customer} 와 달리 null 이면 거르지 않는다. 권한 범위가 아니라 화면의 검색 조건이다. */
    public static Specification<OrderJpaEntity> customerIfGiven(UUID customerId) {
        return customerId == null ? Specification.unrestricted() : customer(customerId);
    }

    /** 주문 번호 앞부분. 기본 키 범위로 읽는다 — 32자리면 정확히 일치. */
    public static Specification<OrderJpaEntity> idStartsWith(OrderIdPrefix prefix) {
        if (prefix == null) {
            return Specification.unrestricted();
        }
        return prefix.exact() ? (root, query, builder) -> builder.equal(root.get("id"), prefix.lowest())
                : (root, query, builder) -> builder.between(root.get("id"), prefix.lowest(), prefix.highest());
    }

    /** 받는 분 이름 일치. ix_order_ship_to_name 으로 그 이름의 행만 읽는다. */
    public static Specification<OrderJpaEntity> shipToName(String shipToName) {
        return shipToName == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("shipToName"), shipToName);
    }

    /**
     * 받는 분 전화번호의 숫자 뒤 4자리. 가상 칼럼을 그대로 비교해야 ix_order_ship_to_phone_last4 를 탄다 — 식을 다시 쓰면 색인을 못 탄다.
     * 칼럼은 {@link OrderPhoneLast4JpaEntity} 에만 있어 IN 서브쿼리로 건다. MySQL 이 준조인으로 바꿔 그 색인으로 id 를 고른 뒤 기본 키로 읽는다.
     */
    public static Specification<OrderJpaEntity> shipToPhoneLast4(String last4) {
        return last4 == null ? Specification.unrestricted() : (root, query, builder) -> {
            Subquery<UUID> matches = query.subquery(UUID.class);
            Root<OrderPhoneLast4JpaEntity> phone = matches.from(OrderPhoneLast4JpaEntity.class);
            matches.select(phone.get("id")).where(builder.equal(phone.get("shipToPhoneLast4"), last4));
            return root.get("id").in(matches);
        };
    }

    public static Specification<OrderJpaEntity> preorderToken(String preorderToken) {
        return preorderToken == null ? Specification.unrestricted()
                : (root, query, builder) -> builder.equal(root.get("preorderToken"), preorderToken);
    }

    /**
     * 그 상품 · 옵션이 든 주문. 조인하지 않고 EXISTS 로 걸어 주문상품이 여럿인 주문도 한 번만 나오고 총계도 주문 수다. 둘 다 주면 한 서브쿼리에
     * 걸어 같은 주문상품이 둘 다 맞아야 한다 — 따로 걸면 상품 A 의 옵션 X 와 상품 B 의 옵션 Y 가 든 주문이 (A, Y) 로도 나온다.
     * MySQL 이 준조인으로 바꿔 ix_order_item_product(상품) · ix_order_item_option(옵션) 으로 그 주문상품부터 읽는다.
     */
    public static Specification<OrderJpaEntity> containsItem(UUID productId, UUID optionId) {
        if (productId == null && optionId == null) {
            return Specification.unrestricted();
        }
        return (root, query, builder) -> {
            Subquery<Integer> items = query.subquery(Integer.class);
            Root<OrderItemJpaEntity> item = items.from(OrderItemJpaEntity.class);
            List<Predicate> matches = new ArrayList<>();
            matches.add(builder.equal(item.get("orderId"), root.get("id")));
            if (productId != null) {
                matches.add(builder.equal(item.get("productId"), productId));
            }
            if (optionId != null) {
                matches.add(builder.equal(item.get("optionId"), optionId));
            }
            items.select(builder.literal(1)).where(matches.toArray(Predicate[]::new));
            return builder.exists(items);
        };
    }

    /** 주문 시각 [from, to). ix_order_created 범위로 읽는다. */
    public static Specification<OrderJpaEntity> createdBetween(Instant from, Instant to) {
        return Specification.allOf(
                from == null ? Specification.unrestricted()
                        : (root, query, builder) -> builder.greaterThanOrEqualTo(root.get("createdAt"), from),
                to == null ? Specification.unrestricted()
                        : (root, query, builder) -> builder.lessThan(root.get("createdAt"), to));
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
