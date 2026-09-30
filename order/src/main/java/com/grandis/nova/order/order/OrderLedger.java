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
     * @param expectedFrom 비어 있으면 안 된다
     * @throws IllegalArgumentException 주문이 없다 — 호출하는 쪽이 먼저 확인한다
     */
    public OrderTransition fire(Long orderId, OrderTrigger trigger, Set<OrderStatus> expectedFrom, EventCause cause) {
        if (expectedFrom.isEmpty()) {
            throw new IllegalArgumentException("기대 상태가 없다");
        }
        OrderStatus from = writer.lockStatus(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + orderId));
        OrderStatus to = expectedFrom.contains(from) ? from.next(trigger).orElse(null) : null;
        if (to == null) {
            return new OrderTransition(false, from);
        }
        Instant now = clock.instant();
        // 행을 잠근 채 읽은 상태를 조건으로 하므로 늘 1행이다. 0 이면 잠금 규칙이 깨진 것이다.
        if (writer.changeStatus(orderId, from, to, now) != 1) {
            throw new IllegalStateException("잠근 주문의 상태가 바뀌었다: orderId=" + orderId);
        }
        writer.appendEvent(new OrderEvent(orderId, writer.eventSequence(orderId), from, to, cause, now));
        return new OrderTransition(true, to);
    }
}
