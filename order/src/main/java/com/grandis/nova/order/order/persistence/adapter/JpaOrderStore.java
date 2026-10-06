package com.grandis.nova.order.order.persistence.adapter;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.exception.OrderAlreadyPlacedException;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.domain.repository.AdminOrderFilter;
import com.grandis.nova.order.order.domain.repository.OrderPosition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.domain.repository.OrderWriter;
import com.grandis.nova.order.order.persistence.entity.OrderJpaEntity;
import com.grandis.nova.order.order.persistence.mapper.OrderMapper;
import com.grandis.nova.order.order.persistence.repository.OrderEventJpaRepository;
import com.grandis.nova.order.order.persistence.repository.OrderItemJpaRepository;
import com.grandis.nova.order.order.persistence.repository.OrderJpaRepository;
import com.grandis.nova.order.order.persistence.repository.OrderSpecifications;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.OrderToken;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 주문 읽기 · 쓰기 포트의 JPA 구현. 엔티티는 여기서 만들고 여기서 도메인으로 바꿔 내보낸다.
 *
 * 쓰기마다 바로 flush 한다. 커밋 때 한꺼번에 flush 되면 제약 위반이 부른 자리가 아닌 커밋에서,
 * Spring 예외로 바뀌지 않은 채(Hibernate ConstraintViolationException) 올라온다. @Repository 가 이 클래스에서 나가는
 * 예외를 DataAccessException 으로 바꾸므로, 여기서 flush 해야 호출하는 쪽이 제약 위반을 한 가지 예외로 다룰 수 있다.
 *
 * 상태 변경(changeStatus)은 벌크 UPDATE 라 영속성 컨텍스트를 거치지 않는다. 바꾼 주문 엔티티만 떼어내고 컨텍스트 전체는
 * 비우지 않는다 — 원장은 호출자의 트랜잭션에 참여하므로, 전체를 비우면 같은 트랜잭션의 다른 엔티티(결제 · 재고)가 떼어져
 * 그 뒤의 변경이 dirty checking 에 잡히지 않고 조용히 유실된다.
 */
@Repository
class JpaOrderStore implements OrderReader, OrderWriter {

    /**
     * 예약 식별 칸의 유일 키. 같은 예약으로 다시 넣으면 둘 다 겹치는데, 여러 키가 겹칠 때 어느 이름으로 알리는지는 MySQL 이
     * 보장하지 않는다(인덱스 재구성 · 버전에 따라 바뀔 수 있다). 그래서 이름으로 가르지 않고 둘 다 같은 예외로 올린다 —
     * 정말 그 예약의 주문인지는 부른 쪽이 새 트랜잭션에서 다시 읽어 판정한다.
     */
    static final Set<String> PREORDER_KEYS = Set.of("uq_order_preorder", "uq_order_preorder_token");

    private final OrderJpaRepository orders;
    private final OrderItemJpaRepository items;
    private final OrderEventJpaRepository events;
    private final EntityManager entityManager;

    JpaOrderStore(OrderJpaRepository orders, OrderItemJpaRepository items, OrderEventJpaRepository events,
                  EntityManager entityManager) {
        this.orders = orders;
        this.items = items;
        this.events = events;
        this.entityManager = entityManager;
    }

    /**
     * 새 주문만 받는다. 이미 저장된 주문이나 결제 대기가 아닌 주문을 넣으면 상태 머신 · 이력을 거치지 않은 주문이 생긴다.
     * 거부는 @Repository 예외 변환을 거쳐 InvalidDataAccessApiUsageException 으로 나간다(원인은 IllegalArgumentException).
     */
    @Override
    public Order insert(Order order) {
        if (!order.isNew() || order.status() != OrderStatus.AWAITING_PAYMENT
                || order.eventSequence() != OrderEvent.FIRST_SEQUENCE) {
            throw new IllegalArgumentException("새 주문(id 없음 · 결제 대기 · 이력 번호 1)만 저장할 수 있다: " + order);
        }
        try {
            return OrderMapper.toDomain(orders.saveAndFlush(OrderMapper.toEntity(order)));
        } catch (DataIntegrityViolationException e) {
            if (PREORDER_KEYS.stream().anyMatch(key -> violates(e, key))) {
                throw new OrderAlreadyPlacedException(order.preorderId(), e);
            }
            throw e;
        }
    }

    @Override
    public void insertItems(Long orderId, List<OrderLine> lines) {
        lines.forEach(line -> entityManager.persist(OrderMapper.toEntity(orderId, line)));
        entityManager.flush();
    }

    @Override
    public void appendEvent(OrderEvent event) {
        entityManager.persist(OrderMapper.toEntity(event));
        entityManager.flush();
    }

    @Override
    public Optional<OrderStatus> lockStatus(Long orderId) {
        return orders.findStatusForUpdate(orderId);
    }

    @Override
    public int changeStatus(Long orderId, OrderStatus from, OrderStatus to, String authorizingProviderOrderId,
                            Instant now) {
        int updated = orders.changeStatus(orderId, from, to, authorizingProviderOrderId, now);
        // 이 주문을 이미 읽어 두었다면 옛 상태를 들고 있다. 그 하나만 떼어내 다음 조회가 DB 에서 읽게 한다.
        // getReference 는 관리 중인 엔티티가 있으면 그것을, 없으면 프록시를 돌려줄 뿐 SELECT 하지 않는다.
        entityManager.detach(entityManager.getReference(OrderJpaEntity.class, orderId));
        return updated;
    }

    @Override
    public Optional<String> authorizingProviderOrderId(Long orderId) {
        return orders.findAuthorizingProviderOrderId(orderId);
    }

    @Override
    public long eventSequence(Long orderId) {
        return orders.findEventSequence(orderId);
    }

    @Override
    public Optional<EventCause> lastEventCause(Long orderId) {
        return events.findFirstByOrderIdOrderByEventSequenceDesc(orderId)
                .map(OrderMapper::toDomain)
                .map(OrderEvent::cause);
    }

    @Override
    public Optional<Order> findById(Long orderId) {
        return orders.findById(orderId).map(OrderMapper::toDomain);
    }

    @Override
    public Optional<Order> findByOrderToken(OrderToken orderToken) {
        return orders.findByOrderToken(orderToken.value()).map(OrderMapper::toDomain);
    }

    @Override
    public Optional<Order> findByPreorderId(Long preorderId) {
        return orders.findByPreorderId(preorderId).map(OrderMapper::toDomain);
    }

    @Override
    public List<OrderItem> findItems(Long orderId) {
        return items.findByOrderIdOrderById(orderId).stream().map(OrderMapper::toDomain).toList();
    }

    @Override
    public List<OrderItem> findItemsByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return items.findByOrderIdInOrderByOrderIdAscIdAsc(orderIds).stream().map(OrderMapper::toDomain).toList();
    }

    /** 총계를 세지 않는다. findAll(Specification, Pageable) 은 쓰지 않는 COUNT 까지 돌린다. */
    @Override
    public List<Order> findByCustomer(Long customerId, OrderPosition after, int limit) {
        return orders.findBy(OrderSpecifications.allOf(
                                OrderSpecifications.customer(customerId),
                                OrderSpecifications.after(after)),
                        query -> query.sortBy(OrderSpecifications.NEWEST_FIRST).limit(limit).all())
                .stream().map(OrderMapper::toDomain).toList();
    }

    @Override
    public OffsetPage<Order> findForAdmin(AdminOrderFilter filter, int page, int size) {
        Page<OrderJpaEntity> found = orders.findAll(OrderSpecifications.allOf(
                        OrderSpecifications.status(filter.status()),
                        OrderSpecifications.source(filter.source())),
                PageRequest.of(page, size, OrderSpecifications.NEWEST_FIRST));
        return OffsetPage.of(found.getContent().stream().map(OrderMapper::toDomain).toList(), page, size,
                found.getTotalElements());
    }

    @Override
    public List<OrderEvent> findEvents(Long orderId, long upToSequence) {
        return events.findByOrderIdAndEventSequenceLessThanEqualOrderByEventSequence(orderId, upToSequence).stream()
                .map(OrderMapper::toDomain).toList();
    }

    /**
     * 위반한 제약이 그것인가. 메시지가 아니라 Hibernate 가 뽑은 제약 이름으로 판정한다.
     * MySQL 은 UNIQUE 위반에 "테이블.인덱스" 로 알려 오므로 끝부분을 본다.
     */
    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return name != null && (name.equals(constraint) || name.endsWith("." + constraint));
            }
        }
        return false;
    }
}
