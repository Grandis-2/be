package com.grandis.nova.order.order.domain.repository;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.exception.OrderAlreadyPlacedException;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.model.OrderLine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 주문 쓰기 포트. {@link com.grandis.nova.order.order.OrderLedger} 만 쓴다 — 다른 곳이 쓰면 이력 없는 전이가 생긴다.
 * 이 규칙은 아키텍처 테스트(OrderArchitectureTest)가 강제한다.
 *
 * 쓰기는 바로 DB 에 반영된다. 제약 위반이 커밋 때가 아니라 부른 자리에서 Spring 예외(DataIntegrityViolationException)로
 * 드러난다. 어떤 제약 위반이든 호출자의 트랜잭션은 rollback-only 가 된다.
 */
public interface OrderWriter {

    /**
     * 새 주문을 저장하고 id · 생성 시각이 채워진 주문을 돌려준다.
     *
     * @throws OrderAlreadyPlacedException 예약 식별 칸(내부 id · UUID)이 이미 쓰였다. 그 예약의 주문인지는 부른 쪽이 확인한다
     */
    Order insert(Order order);

    void insertItems(Long orderId, List<OrderLine> lines);

    void appendEvent(OrderEvent event);

    /**
     * 상태를 읽으며 주문 행을 잠근다. 판정하고 반영할 때까지 다른 트랜잭션이 상태를 바꾸지 못한다.
     * 잠금 순서는 주문 행 → 결제 행 → 재고 행이다. 뒤의 행을 먼저 잠근 뒤 이걸 부르지 않는다(교착).
     */
    Optional<OrderStatus> lockStatus(Long orderId);

    /**
     * 상태가 from 일 때만 to 로 바꾸고 이력 번호를 1 올린다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int changeStatus(Long orderId, OrderStatus from, OrderStatus to, Instant now);

    /** 방금 올린 이력 번호. 같은 트랜잭션의 UPDATE 가 행을 잠그고 있어 다른 트랜잭션이 끼어들 수 없다. */
    long eventSequence(Long orderId);
}
