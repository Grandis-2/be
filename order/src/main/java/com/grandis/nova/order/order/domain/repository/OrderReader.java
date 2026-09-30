package com.grandis.nova.order.order.domain.repository;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.vo.OrderToken;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 주문 읽기 포트. 조회 · 판정 유스케이스는 이것만 주입받는다 — 쓰기({@link OrderWriter})를 함께 쥐면
 * 원장을 거치지 않은 전이를 만들 수 있다.
 *
 * 잠그지 않고 읽는다. 읽은 상태로 전이 여부를 정하지 않는다 — 그 판정은 원장의 fire 가 잠근 채로 한다.
 */
public interface OrderReader {

    Optional<Order> findById(Long orderId);

    /** 공개 토큰으로 찾는다. 조회 API 는 이 값만 받는다. */
    Optional<Order> findByOrderToken(OrderToken orderToken);

    /** 예약의 주문. 예약당 주문은 평생 하나라(uq_order_preorder) 0~1건이다. */
    Optional<Order> findByPreorderId(Long preorderId);

    List<OrderItem> findItems(Long orderId);

    /**
     * 여러 주문의 항목을 한 번에(주문마다 다시 묻지 않는다). 주문 id · 항목 id 순이다.
     *
     * findItems(Long) 의 오버로드로 두지 않는다 — 인자 타입만 다른 오버로드는 목 매처(any())나 null 과 만나면
     * 호출이 모호해져 컴파일이 깨진다. 이 포트의 메서드 이름은 겹치지 않게 둔다(OrderReaderContractTest).
     */
    List<OrderItem> findItemsByOrderIds(Collection<Long> orderIds);

    /**
     * 회원의 주문, 최신순((created_at, id) 내림차순). ix_order_member_created 를 탄다.
     *
     * @param after 이 자리보다 뒤(더 오래된 것)만. 첫 페이지면 null
     * @param limit 최대 건수. 다음 페이지가 있는지 보려면 한 건 더 달라고 한다
     */
    List<Order> findByCustomer(Long customerId, OrderPosition after, int limit);

    /** 관리자 목록, 최신순. 총계를 함께 센다. page 는 0 부터. */
    OffsetPage<Order> findForAdmin(AdminOrderFilter filter, int page, int size);

    /**
     * 이력, 번호순. upToSequence 까지만 읽는다 — 주문을 읽은 뒤 커밋된 전이의 이력이 섞이면
     * 응답의 상태와 이력의 마지막이 어긋난다(READ COMMITTED 는 SELECT 마다 스냅샷이 다르다).
     * 읽어 둔 주문의 eventSequence 를 넘긴다.
     */
    List<OrderEvent> findEvents(Long orderId, long upToSequence);
}
