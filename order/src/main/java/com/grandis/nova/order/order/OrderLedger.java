package com.grandis.nova.order.order;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.exception.OrderAlreadyPlacedException;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderWriter;
import com.grandis.nova.order.order.vo.EventCause;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 주문 상태를 바꾸는 유일한 길. 쓰기 포트({@link OrderWriter})는 여기서만 쓴다(아키텍처 테스트가 강제).
 * 호출하는 쪽은 사건({@link OrderTrigger})만 알리고, 다음 상태는 상태 머신({@link OrderStatus#next})이 정한다.
 *
 * 사건 하나의 처리:
 * 주문 행 잠금 읽기 → 기대 상태 확인 → 상태 머신 판정 → 현재 상태 조건부 UPDATE(이력 번호 증가) → 이력 INSERT.
 * 이 모두가 호출한 쪽의 트랜잭션 하나에서 일어난다.
 *
 * <b>트랜잭션 계약</b>
 * <ul>
 *   <li>스스로 트랜잭션을 열지 않는다(MANDATORY). 전이는 늘 다른 변경(아웃박스 · 결제 행)과 한 트랜잭션이어야 해서,
 *       여기서 따로 커밋되면 그 원자성이 깨진다. 트랜잭션 없이 부르면 바로 실패한다.</li>
 *   <li>여기서 나가는 예외는 모두 호출자의 트랜잭션을 rollback-only 로 만든다. 잡아서 이어 쓰지 않는다 —
 *       기존 주문 재조회 같은 후속 작업은 새 트랜잭션에서 한다. 그래서 사용자 입력 검증(OrderDraft · EventCause
 *       생성)은 원장을 부르기 전에 끝나 있어야 한다.</li>
 *   <li>원장 안에서 나는 IllegalArgumentException 은 모두 호출하는 코드의 잘못이다 — 없는 주문, 빈 기대 상태,
 *       그리고 지금 받지 않는 주문({@link Order#place} 의 수락 규칙: 사전예약은 옵션 하나 · 수량 1 · 보증 없음, 바로 구매는 옵션
 *       하나). 수락 규칙은 사용자 입력이 아니라 만들 수 있는 주문의 범위라, 초안을 만드는 생성 유스케이스는 어길 수 없다.</li>
 *   <li>같은 예약으로 동시에 생성하다 먼저 들어간 쪽이 롤백되면 기다리던 쪽끼리 교착할 수 있다(InnoDB 중복 키 S 잠금 →
 *       삽입 의도 잠금). 생성 유스케이스는 교착 예외를 재시도 대상으로 둔다.</li>
 * </ul>
 *
 * 잠금 순서: 주문 행 → 결제 행 → 재고 행. 이 원장이 늘 주문 행을 먼저 잠그므로, 결제 · 재고를 함께 바꾸는
 * 유스케이스는 원장을 먼저 부른다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class OrderLedger {

    private static final Set<OrderTrigger> PAYMENT_TRIGGERS = EnumSet.of(OrderTrigger.PAYMENT_REQUESTED,
            OrderTrigger.PAYMENT_APPROVED, OrderTrigger.PAYMENT_DECLINED, OrderTrigger.PAYMENT_UNCONFIRMED);

    private final OrderWriter writer;
    private final Clock clock;

    public OrderLedger(OrderWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * 새 주문과 항목 · 첫 이력(번호 1, from 없음)을 저장한다. 금액은 항목에서 계산한다.
     *
     * @throws OrderAlreadyPlacedException 예약 식별 칸(내부 id · UUID)이 이미 쓰였다 — 그 예약의 주문인지, 기존 주문을
     *                                     돌려줄지(200) 취소된 주문이라 거절할지(409)는 생성 유스케이스가 새 트랜잭션에서 조회해 판정한다
     */
    public Order place(OrderDraft draft, EventCause cause) {
        Order order = writer.insert(Order.place(draft, clock.instant()));
        writer.insertItems(order.id(), draft.lines());
        writer.appendEvent(OrderEvent.placed(order.id(), order.status(), cause, clock.instant()));
        return order;
    }

    /**
     * 주문이 expectedFrom 중 하나일 때만 사건을 적용한다. 판정은 주문 행을 잠근 뒤에 한다.
     *
     * expectedFrom 은 호출하는 쪽이 "이 상태라고 보고 이 사건을 고른" 전제다. 잠그지 않고 읽은 상태로 사건을 골랐다면
     * 그사이 상태가 바뀌었을 수 있다 — 예: 만료 취소를 "미결제니까" 로 골랐는데 그 순간 결제가 승인됐다. 전제가 틀리면
     * 아무것도 바꾸지 않고 지금 상태를 돌려준다. 그 상태를 보고 무엇으로 응답할지는 호출하는 쪽이 정한다.
     *
     * applied = false 는 네 경우다: 전제와 다른 상태 · 중복(이미 반영) · 받아들일 수 없는 사건 · 결과 대기.
     * 원장은 구분하지 않는다 — 돌려준 status 를 보고 판단한다.
     *
     * 결제 사건(요청 · 승인 · 거절 · 결과 모름)은 여기로 알리지 않는다 — 결제창 번호를 함께 다루는 {@link #requestPayment} ·
     * {@link #settlePayment} · {@link #cancelUnconfirmedReleasingStock} 를 쓴다.
     *
     * @param expectedFrom 비어 있으면 안 된다
     * @throws IllegalArgumentException 주문이 없다 · 결제 사건이다 — 호출하는 쪽의 잘못이다
     */
    public OrderTransition fire(UUID orderId, OrderTrigger trigger, Set<OrderStatus> expectedFrom, EventCause cause) {
        if (PAYMENT_TRIGGERS.contains(trigger)) {
            throw new IllegalArgumentException("결제 사건은 requestPayment · settlePayment 로 알린다: " + trigger);
        }
        if (expectedFrom.isEmpty()) {
            throw new IllegalArgumentException("기대 상태가 없다");
        }
        OrderStatus from = lock(orderId);
        if (!expectedFrom.contains(from)) {
            return new OrderTransition(false, from);
        }
        return transition(orderId, from, trigger, null, cause, clock.instant());
    }

    /**
     * 예약 취소 요청: {@link #fire} 의 CANCEL_REQUESTED 와 같고, 취소 중으로 바꿨으면(결제된 주문) 같은 잠금 안에서 그 취소 시도의
     * 순번을 적는다. 환불이 끝나면 그 순번으로 preorder 에 결과를 알린다({@link #notePreorderCancelReported}).
     * 미결제라 바로 취소했으면 적지 않는다 — 결과는 호출하는 쪽이 같은 트랜잭션에서 보낸다.
     *
     * @param expectedFrom 비어 있으면 안 된다
     * @throws IllegalArgumentException 주문이 없다
     * @throws IllegalStateException    취소 중으로 바꾼 주문에 순번을 적지 못했다 — 사전예약 주문이 아니다(호출하는 코드의 잘못)
     */
    public OrderTransition requestPreorderCancel(UUID orderId, Set<OrderStatus> expectedFrom, long cancelSequence,
                                                 EventCause cause) {
        OrderTransition transition = fire(orderId, OrderTrigger.CANCEL_REQUESTED, expectedFrom, cause);
        // 잠근 채 방금 취소 중으로 바꿨으므로 사전예약 주문이면 늘 1행이다
        if (transition.applied() && transition.status() == OrderStatus.CANCELING
                && writer.markPreorderCancelSequence(orderId, cancelSequence) != 1) {
            throw new IllegalStateException("취소 중으로 바꾼 주문에 취소 시도 순번을 적지 못했다: orderId=" + orderId);
        }
        return transition;
    }

    /**
     * 환불이 끝나 취소된 사전예약 주문의 결과를 preorder 에 알린다고 적고, 결과에 실을 순번을 돌려준다. 이 표식이 있으면 다시 받은 예약
     * 취소 요청은 결과를 적지 않으므로, 순번을 돌려받으면 호출하는 쪽은 반드시 같은 트랜잭션에서 그 순번으로 결과를 아웃박스에 적는다 —
     * 적지 않고 커밋하면 결과가 사라진다. 이 주문을 방금 취소로 바꾼 트랜잭션(환불 완료 전이가 적용된 때)에서만 부른다.
     *
     * 순번이 없거나(미결제 취소 · 구버전이 취소 중으로 바꾼 주문 · 사전예약이 아님) 이미 적었으면 아무것도 바꾸지 않고 비어 있다.
     */
    public Optional<Long> notePreorderCancelReported(UUID orderId) {
        // UPDATE 가 취소됨 · 순번 있음 · 아직 안 적음을 조건으로 행을 잠그므로, 1행이면 이어 읽는 순번은 늘 있다
        if (writer.markPreorderCancelReported(orderId, clock.instant()) == 0) {
            return Optional.empty();
        }
        return Optional.of(writer.preorderCancelSequence(orderId).orElseThrow());
    }

    /**
     * 결제 승인 요청: 결제 대기일 때만 승인 중으로 바꾸고 그 결제창 번호를 적는다.
     *
     * @param providerOrderId 승인할 결제창(결제사 주문 번호)
     */
    public OrderTransition requestPayment(UUID orderId, String providerOrderId, EventCause cause) {
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new IllegalArgumentException("결제창 번호가 없다");
        }
        OrderStatus from = lock(orderId);
        if (from != OrderStatus.AWAITING_PAYMENT) {
            return new OrderTransition(false, from);
        }
        return transition(orderId, from, OrderTrigger.PAYMENT_REQUESTED, providerOrderId, cause, clock.instant());
    }

    /**
     * 결제 결과를 반영한다. 승인 중일 때만 바꾸고 결제창 번호를 지운다. 승인이면 같은 트랜잭션에서 결제 시각을 적는다.
     *
     * 거절(되돌림 포함)은 그 결제창의 승인 중일 때만 반영한다 — 늦게 온 이전 결제창의 거절이 새 결제창의 승인 중에 주문을
     * 결제 대기로 되돌리면, 새 결제창의 승인은 전제가 맞지 않아 반영되지 못한다(돈은 나갔는데 미결제).
     * 승인은 결제창을 대조하지 않는다 — 대상당 성공 결제는 하나라(payment D13) 언제 와도 그 주문의 결제다.
     *
     * @param result     PAYMENT_APPROVED · PAYMENT_DECLINED
     * @param approvedAt 결제사 승인 시각. 승인 API 응답에는 없어 null 이면 반영한 시각(이력의 시각)을 결제 시각으로 적는다. 거절이면 null
     */
    public OrderTransition settlePayment(UUID orderId, OrderTrigger result, String providerOrderId, Instant approvedAt,
                                         EventCause cause) {
        if (result != OrderTrigger.PAYMENT_APPROVED && result != OrderTrigger.PAYMENT_DECLINED) {
            throw new IllegalArgumentException("결제 결과가 아니다: " + result);
        }
        if (result == OrderTrigger.PAYMENT_DECLINED && approvedAt != null) {
            throw new IllegalArgumentException("거절에는 승인 시각이 없다");
        }
        OrderStatus from = lock(orderId);
        if (from != OrderStatus.AUTHORIZING) {
            return new OrderTransition(false, from);
        }
        if (result == OrderTrigger.PAYMENT_DECLINED
                && !writer.authorizingProviderOrderId(orderId).orElseThrow().equals(providerOrderId)) {
            return new OrderTransition(false, from);
        }
        Instant now = clock.instant();
        OrderTransition settled = transition(orderId, from, result, null, cause, now);
        // 잠근 채 방금 결제 확인 대기로 바꿨으므로 늘 1행이다
        if (result == OrderTrigger.PAYMENT_APPROVED
                && writer.markPaid(orderId, approvedAt != null ? approvedAt : now) != 1) {
            throw new IllegalStateException("승인을 반영한 주문에 결제 시각을 적지 못했다: orderId=" + orderId);
        }
        return settled;
    }

    /**
     * 환불이 확정 실패했다고 적는다 — 주문의 환불 실패 시각을 채우고 이력을 한 줄 남긴다. 상태는 그대로다. 취소 중이면 환불이 끝나지 않아
     * 취소로 끝낼 수 없고, 자동 환불을 요청한 취소된 주문이면 이미 취소다. 다시 여는 것은 사람의 판단이다(U1). 이력 번호는 전이처럼 1 올린다.
     * 그 두 경우이고 아직 적지 않았을 때만 적는다 — 같은 알림을 다시 받아도 한 번이다.
     *
     * @return 적었으면 true. 그 두 경우가 아니거나 이미 적었으면 false
     * @throws IllegalArgumentException 주문이 없다
     */
    public boolean noteRefundFailed(UUID orderId) {
        OrderStatus from = lock(orderId);
        if (from != OrderStatus.CANCELING && from != OrderStatus.CANCELED) {
            return false;
        }
        Instant now = clock.instant();
        if (writer.markRefundFailed(orderId, now) == 0) {
            return false;
        }
        writer.appendEvent(new OrderEvent(orderId, writer.eventSequence(orderId), from, from, EventCause.refundFailed(),
                now));
        return true;
    }

    /**
     * 관리자가 멈춘 환불을 다시 보냈다고 적는다 — 환불 실패 시각을 지우고 이력(REFUND_RETRIED)을 한 줄 남긴다. 상태는 그대로다. 지워야 다시
     * 실패했을 때 그 실패를 또 적는다({@link #noteRefundFailed} 는 실패 시각이 없을 때만 적는다). 취소 중 주문 · 자동 환불이 끝나지 않은 취소된
     * 주문에만.
     *
     * 이 기록이 늦어 그사이 다시 보낸 환불의 새 실패가 먼저 적혔으면(실패 시각이 requestedAt 뒤) 아무것도 하지 않는다 — 지우면 그 실패가
     * 사라지고, 이력만 남겨도 마지막 이력이 실패가 아니게 돼 화면의 실패 표시가 풀린다(OrderView). 다시 보낸 기록은 payment 의 조치 기록에 있다.
     * 막지 못하는 경우가 남는다 — 옛 실패 시각이 있는 채 새 실패가 먼저 닿으면 그 실패는 적히지 않고 이 기록이 옛 시각을 지운다. 환불 결과에
     * 거래 id 가 없어 가를 수 없다({@link com.grandis.nova.order.order.cancel.RefundIssues} 의 한계). 그때는 payment 목록이 맞다.
     *
     * @param requestedAt 관리자 조치를 payment 에 요청하기 전의 시각
     * @return 적었으면 true
     * @throws IllegalArgumentException 주문이 없다
     */
    public boolean noteRefundRetried(UUID orderId, Instant requestedAt, EventCause cause) {
        OrderStatus from = lock(orderId);
        Instant now = clock.instant();
        if (writer.markRefundRetried(orderId, requestedAt, now) == 0) {
            return false;
        }
        writer.appendEvent(new OrderEvent(orderId, writer.eventSequence(orderId), from, from, cause, now));
        return true;
    }

    /**
     * 자동 환불이 끝났다고 적는다 — 완료 시각과 이력(REFUND_COMPLETED, 취소 → 취소)을 한 줄 남긴다. 주문은 이미 취소라 상태는 그대로다.
     * 자동 환불을 요청한 취소된 주문이고 아직 적지 않았을 때만 — 같은 결과를 다시 받아도 한 번이다.
     *
     * @return 적었으면 true
     * @throws IllegalArgumentException 주문이 없다
     */
    public boolean noteAutoRefundCompleted(UUID orderId, EventCause cause) {
        OrderStatus from = lock(orderId);
        Instant now = clock.instant();
        if (writer.markAutoRefundCompleted(orderId, now) == 0) {
            return false;
        }
        writer.appendEvent(new OrderEvent(orderId, writer.eventSequence(orderId), from, from, cause, now));
        return true;
    }

    /**
     * 결제 대기인 일반 주문을 취소하고 재고 반환 표식을 적는다(취소와 같은 UPDATE). 재고 행의 반환은 호출하는 쪽이 같은 트랜잭션에서
     * 한다 — 이 메서드가 true 를 돌려준 때만, 그래서 한 번만.
     *
     * 결제 대기가 아니면(승인 중 · 결제됨 · 이미 취소) 아무것도 바꾸지 않고 지금 상태를 돌려준다.
     *
     * @throws IllegalArgumentException 주문이 없다
     * @throws IllegalStateException    결제 대기인데 바꾸지 못했다 — 사전예약 주문이거나 표식이 이미 있다(호출하는 코드의 잘못 · 데이터 어긋남)
     */
    public OrderTransition cancelUnpaidReleasingStock(UUID orderId, EventCause cause) {
        OrderStatus from = lock(orderId);
        if (from != OrderStatus.AWAITING_PAYMENT) {
            return new OrderTransition(false, from);
        }
        OrderStatus to = from.next(OrderTrigger.CANCEL_REQUESTED).orElseThrow();
        Instant now = clock.instant();
        if (writer.cancelReleasingStock(orderId, now) != 1) {
            throw new IllegalStateException("결제 대기인 일반 주문을 취소 · 반환 표식하지 못했다: orderId=" + orderId);
        }
        writer.appendEvent(new OrderEvent(orderId, writer.eventSequence(orderId), from, to, cause, now));
        return new OrderTransition(true, to);
    }

    /**
     * 결제 결과를 끝내 모르는 승인 중 일반 주문(장바구니 · 바로 구매)을 취소하고 재고 반환 표식을 적는다(취소와 같은 UPDATE). 그 결제창 번호는
     * 늦은 승인을 가리기 위해 남긴다({@link #requestLateRefund}). 재고 행의 반환은 호출하는 쪽이 같은 트랜잭션에서 한다 — true 를 돌려준 때만.
     *
     * 승인 중이 아니면(그사이 결과가 왔다 · 이미 취소) 아무것도 바꾸지 않고 지금 상태를 돌려준다.
     *
     * @throws IllegalArgumentException 주문이 없다
     * @throws IllegalStateException    승인 중인데 바꾸지 못했다 — 장바구니 · 바로 구매 주문이 아니거나(사전예약 · 드로우) 표식이 이미 있다
     *                                  (호출하는 코드의 잘못 · 데이터 어긋남)
     */
    public OrderTransition cancelUnconfirmedReleasingStock(UUID orderId, EventCause cause) {
        OrderStatus from = lock(orderId);
        if (from != OrderStatus.AUTHORIZING) {
            return new OrderTransition(false, from);
        }
        OrderStatus to = from.next(OrderTrigger.PAYMENT_UNCONFIRMED).orElseThrow();
        Instant now = clock.instant();
        if (writer.cancelUnconfirmedReleasingStock(orderId, now) != 1) {
            throw new IllegalStateException("승인 중인 일반 주문을 취소 · 반환 표식하지 못했다: orderId=" + orderId);
        }
        writer.appendEvent(new OrderEvent(orderId, writer.eventSequence(orderId), from, to, cause, now));
        return new OrderTransition(true, to);
    }

    /**
     * 결과를 모른 채 취소한 주문에 그 결제창의 승인이 늦게 왔다 — 자동 환불을 요청했다고 적고 이력을 한 줄 남긴다(상태는 취소 그대로).
     * 환불 요청 메시지는 호출하는 쪽이 같은 트랜잭션에서 아웃박스에 적는다 — {@link LateRefund#REQUESTED} 를 돌려준 때만, 그래서 한 번만.
     *
     * @param providerOrderId 승인된 결제창. 취소할 때 남긴 결제창과 같아야 한다
     * @param amountMatches   승인 금액이 주문 총액과 같은가. 다르면 요청하지 않는다
     * @throws IllegalArgumentException 주문이 없다
     */
    public LateRefund requestLateRefund(UUID orderId, String providerOrderId, boolean amountMatches, EventCause cause) {
        OrderStatus from = lock(orderId);
        if (from != OrderStatus.CANCELED) {
            return LateRefund.NOT_ELIGIBLE;
        }
        if (!amountMatches) {
            return writer.unconfirmedProviderOrderId(orderId).filter(providerOrderId::equals).isPresent()
                    ? LateRefund.AMOUNT_MISMATCH : LateRefund.NOT_ELIGIBLE;
        }
        Instant now = clock.instant();
        if (writer.markAutoRefundRequested(orderId, providerOrderId, now) == 0) {
            // 같은 결제창의 승인을 다시 받았을 때만 중복이다. 결제창이 다르면 이미 요청했어도 이 승인은 자동 환불이 덮지 않는다
            boolean sameAttempt = writer.unconfirmedProviderOrderId(orderId).filter(providerOrderId::equals).isPresent();
            return sameAttempt && writer.autoRefundRequestedAt(orderId).isPresent()
                    ? LateRefund.ALREADY_REQUESTED : LateRefund.NOT_ELIGIBLE;
        }
        writer.appendEvent(new OrderEvent(orderId, writer.eventSequence(orderId), from, from, cause, now));
        return LateRefund.REQUESTED;
    }

    /** {@link #requestLateRefund} 의 결과. */
    public enum LateRefund {
        /** 이번에 요청했다 — 호출하는 쪽이 환불 요청을 적는다. */
        REQUESTED,
        /** 같은 결제창의 승인으로 이미 요청했다(같은 승인을 다시 받았다). */
        ALREADY_REQUESTED,
        /** 자동 환불 대상이 아니다 — 결과를 모른 채 취소한 주문이 아니거나, 취소할 때 남긴 결제창과 다르다(이미 요청했어도). */
        NOT_ELIGIBLE,
        /** 결과를 모른 채 취소한 주문의 그 결제창 승인인데 금액이 주문 총액과 다르다 — 요청하지 않고 사람이 본다. */
        AMOUNT_MISMATCH
    }

    /**
     * 주문 행을 잠그고 지금 상태를 읽는다 — 잠그지 않고 읽어 둔 주문을 돌려주거나 판단하기 전에 다시 보는 데 쓴다. 바꾸지 않는다.
     *
     * @throws IllegalArgumentException 주문이 없다
     */
    public OrderStatus lockedStatus(UUID orderId) {
        return lock(orderId);
    }

    private OrderStatus lock(UUID orderId) {
        return writer.lockStatus(orderId).orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + orderId));
    }

    /** 잠근 주문을 사건대로 바꾼다. 상태 머신이 받지 않는 사건이면 그대로 둔다. */
    private OrderTransition transition(UUID orderId, OrderStatus from, OrderTrigger trigger,
                                       String authorizingProviderOrderId, EventCause cause, Instant now) {
        OrderStatus to = from.next(trigger).orElse(null);
        if (to == null) {
            return new OrderTransition(false, from);
        }
        // 행을 잠근 채 읽은 상태를 조건으로 하므로 늘 1행이다. 0 이면 잠금 규칙이 깨진 것이다.
        if (writer.changeStatus(orderId, from, to, authorizingProviderOrderId, now) != 1) {
            throw new IllegalStateException("잠근 주문의 상태가 바뀌었다: orderId=" + orderId);
        }
        writer.appendEvent(new OrderEvent(orderId, writer.eventSequence(orderId), from, to, cause, now));
        return new OrderTransition(true, to);
    }
}
