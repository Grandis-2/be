package com.grandis.nova.order.order.domain.repository;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.exception.OrderAlreadyPlacedException;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.model.OrderLine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    void insertItems(UUID orderId, List<OrderLine> lines);

    void appendEvent(OrderEvent event);

    /**
     * 상태를 읽으며 주문 행을 잠근다. 판정하고 반영할 때까지 다른 트랜잭션이 상태를 바꾸지 못한다.
     * 잠금 순서는 주문 행 → 결제 행 → 재고 행이다. 뒤의 행을 먼저 잠근 뒤 이걸 부르지 않는다(교착).
     */
    Optional<OrderStatus> lockStatus(UUID orderId);

    /**
     * 상태가 from 일 때만 to 로 바꾸고 이력 번호를 1 올린다. 승인 중 결제창 번호도 함께 바꾼다 —
     * AUTHORIZING 으로 갈 때만 값이 있고 나머지는 지운다(ck_order_authorizing_attempt).
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int changeStatus(UUID orderId, OrderStatus from, OrderStatus to, String authorizingProviderOrderId, Instant now);

    /**
     * 결제 대기인 일반 주문을 취소로 바꾸며 재고 반환 표식을 같은 UPDATE 에서 적는다(ck_order_stock_released_canceled). 이력 번호를 1 올린다.
     * 표식이 이미 있으면 바꾸지 않는다 — 반환은 한 번만이다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int cancelReleasingStock(UUID orderId, Instant now);

    /**
     * 승인 중인 장바구니 · 바로 구매 주문을 취소로 바꾸며 재고 반환 표식을 같은 UPDATE 에서 적는다. 승인 중 결제창 번호는 "결과를 모른 결제창" 칸으로 옮긴다
     * (ck_order_unconfirmed_attempt) — 늦게 온 승인이 그 결제창일 때만 자동 환불한다. 이력 번호를 1 올린다. 표식이 이미 있으면 바꾸지 않는다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int cancelUnconfirmedReleasingStock(UUID orderId, Instant now);

    /**
     * 결과를 모른 채 취소한 주문에, 그 결제창의 늦은 승인을 자동 환불하라고 요청한 시각을 적는다(ck_order_auto_refund). 이력 번호를 1 올린다.
     * 취소됨 · 결제창 일치 · 아직 안 적음일 때만 — 같은 승인을 다시 받아도 한 번이다. {@link #lockStatus} 로 행을 잠근 뒤에 부른다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int markAutoRefundRequested(UUID orderId, String providerOrderId, Instant now);

    /**
     * 취소 중인 주문 · 자동 환불을 요청한 취소된 주문에 환불 실패 시각을 적는다(ck_order_refund_failed). 이력 번호를 1 올린다.
     * 이미 적혀 있으면 바꾸지 않는다 — 같은 실패는 한 번만 적는다. {@link #lockStatus} 로 행을 잠근 뒤에 부른다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int markRefundFailed(UUID orderId, Instant now);

    /**
     * 결제 확인 대기인 주문에 결제 시각을 적는다(ck_order_paid). 이력 번호는 올리지 않는다 — 같은 트랜잭션의 승인 전이가 이미 올렸다.
     * 이미 적혀 있으면 바꾸지 않는다. {@link #lockStatus} 로 행을 잠근 뒤에 부른다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int markPaid(UUID orderId, Instant paidAt);

    /**
     * 취소 중인 사전예약 주문에 그 취소 시도의 순번을 적는다(ck_order_preorder_cancel_sequence). 이력 번호는 올리지 않는다 — 같은
     * 트랜잭션의 취소 전이가 이미 올렸다. 이미 적혀 있으면 바꾸지 않는다. {@link #lockStatus} 로 행을 잠근 뒤에 부른다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int markPreorderCancelSequence(UUID orderId, long cancelSequence);

    /**
     * 순번이 있는 취소된 주문에 그 취소 결과를 알린 시각을 적는다(ck_order_preorder_cancel_reported). 이미 적혀 있으면 바꾸지 않는다.
     * {@link #lockStatus} 로 행을 잠근 뒤에 부른다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int markPreorderCancelReported(UUID orderId, Instant now);

    /** 저장한 취소 시도 순번. 없으면 비어 있다. {@link #lockStatus} 로 행을 잠근 뒤에 부른다. */
    Optional<Long> preorderCancelSequence(UUID orderId);

    /** 결과를 모른 채 취소할 때 남긴 결제창. 그렇게 취소한 주문이 아니면 비어 있다. {@link #lockStatus} 로 행을 잠근 뒤에 부른다. */
    Optional<String> unconfirmedProviderOrderId(UUID orderId);

    /** 자동 환불을 요청한 시각. 요청하지 않았으면 비어 있다. {@link #lockStatus} 로 행을 잠근 뒤에 부른다. */
    Optional<Instant> autoRefundRequestedAt(UUID orderId);

    /** 승인 중 결제창 번호. 승인 중이 아니면 비어 있다. {@link #lockStatus} 로 행을 잠근 뒤에 부른다. */
    Optional<String> authorizingProviderOrderId(UUID orderId);

    /** 방금 올린 이력 번호. 같은 트랜잭션의 UPDATE 가 행을 잠그고 있어 다른 트랜잭션이 끼어들 수 없다. */
    long eventSequence(UUID orderId);
}
