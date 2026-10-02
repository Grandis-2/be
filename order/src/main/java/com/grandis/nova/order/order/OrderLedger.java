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
import com.grandis.nova.order.order.vo.OrderToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

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
 *       그리고 지금 받지 않는 주문({@link Order#place} 의 수락 규칙: 사전예약 · 옵션 하나 · 수량 1). 수락 규칙은
 *       사용자 입력이 아니라 이 에픽이 만들 수 있는 주문의 범위라, 예약에서 초안을 만드는 생성 유스케이스는 어길 수 없다.</li>
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

    private static final Set<OrderTrigger> PAYMENT_TRIGGERS = EnumSet.of(
            OrderTrigger.PAYMENT_REQUESTED, OrderTrigger.PAYMENT_APPROVED, OrderTrigger.PAYMENT_DECLINED);

    private final OrderWriter writer;
    private final Clock clock;

    public OrderLedger(OrderWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    /**
     * 새 주문과 항목 · 첫 이력(번호 1, from 없음)을 저장한다. 금액은 항목에서 계산하고 공개 토큰은 여기서 발급한다.
     *
     * @throws OrderAlreadyPlacedException 예약 식별 칸(내부 id · UUID)이 이미 쓰였다 — 그 예약의 주문인지, 기존 주문을
     *                                     돌려줄지(200) 취소된 주문이라 거절할지(409)는 생성 유스케이스가 새 트랜잭션에서 조회해 판정한다
     */
    public Order place(OrderDraft draft, EventCause cause) {
        Order order = writer.insert(Order.place(draft, OrderToken.issue()));
        writer.insertItems(order.id(), draft.lines());
        writer.appendEvent(OrderEvent.placed(order.id(), cause, clock.instant()));
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
     * 결제 사건(요청 · 승인 · 거절)은 여기로 알리지 않는다 — 결제창 번호를 함께 다루는 {@link #requestPayment} ·
     * {@link #settlePayment} 를 쓴다.
     *
     * @param expectedFrom 비어 있으면 안 된다
     * @throws IllegalArgumentException 주문이 없다 · 결제 사건이다 — 호출하는 쪽의 잘못이다
     */
    public OrderTransition fire(Long orderId, OrderTrigger trigger, Set<OrderStatus> expectedFrom, EventCause cause) {
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
        return transition(orderId, from, trigger, null, cause);
    }

    /**
     * 결제 승인 요청: 결제 대기일 때만 승인 중으로 바꾸고 그 결제창 번호를 적는다.
     *
     * @param providerOrderId 승인할 결제창(결제사 주문 번호)
     */
    public OrderTransition requestPayment(Long orderId, String providerOrderId, EventCause cause) {
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new IllegalArgumentException("결제창 번호가 없다");
        }
        OrderStatus from = lock(orderId);
        if (from != OrderStatus.AWAITING_PAYMENT) {
            return new OrderTransition(false, from);
        }
        return transition(orderId, from, OrderTrigger.PAYMENT_REQUESTED, providerOrderId, cause);
    }

    /**
     * 결제 결과를 반영한다. 승인 중일 때만 바꾸고 결제창 번호를 지운다.
     *
     * 거절(되돌림 포함)은 그 결제창의 승인 중일 때만 반영한다 — 늦게 온 이전 결제창의 거절이 새 결제창의 승인 중에 주문을
     * 결제 대기로 되돌리면, 새 결제창의 승인은 전제가 맞지 않아 반영되지 못한다(돈은 나갔는데 미결제).
     * 승인은 결제창을 대조하지 않는다 — 대상당 성공 결제는 하나라(payment D13) 언제 와도 그 주문의 결제다.
     *
     * @param result PAYMENT_APPROVED · PAYMENT_DECLINED
     */
    public OrderTransition settlePayment(Long orderId, OrderTrigger result, String providerOrderId, EventCause cause) {
        if (result != OrderTrigger.PAYMENT_APPROVED && result != OrderTrigger.PAYMENT_DECLINED) {
            throw new IllegalArgumentException("결제 결과가 아니다: " + result);
        }
        OrderStatus from = lock(orderId);
        if (from != OrderStatus.AUTHORIZING) {
            return new OrderTransition(false, from);
        }
        if (result == OrderTrigger.PAYMENT_DECLINED
                && !writer.authorizingProviderOrderId(orderId).orElseThrow().equals(providerOrderId)) {
            return new OrderTransition(false, from);
        }
        return transition(orderId, from, result, null, cause);
    }

    private OrderStatus lock(Long orderId) {
        return writer.lockStatus(orderId).orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + orderId));
    }

    /** 잠근 주문을 사건대로 바꾼다. 상태 머신이 받지 않는 사건이면 그대로 둔다. */
    private OrderTransition transition(Long orderId, OrderStatus from, OrderTrigger trigger,
                                       String authorizingProviderOrderId, EventCause cause) {
        OrderStatus to = from.next(trigger).orElse(null);
        if (to == null) {
            return new OrderTransition(false, from);
        }
        Instant now = clock.instant();
        // 행을 잠근 채 읽은 상태를 조건으로 하므로 늘 1행이다. 0 이면 잠금 규칙이 깨진 것이다.
        if (writer.changeStatus(orderId, from, to, authorizingProviderOrderId, now) != 1) {
            throw new IllegalStateException("잠근 주문의 상태가 바뀌었다: orderId=" + orderId);
        }
        writer.appendEvent(new OrderEvent(orderId, writer.eventSequence(orderId), from, to, cause, now));
        return new OrderTransition(true, to);
    }
}
