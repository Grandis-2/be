package com.grandis.nova.order.order.domain.repository;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.vo.OrderToken;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문 읽기 포트. 조회 · 판정 유스케이스는 이것만 주입받는다 — 쓰기({@link OrderWriter})를 함께 쥐면
 * 원장을 거치지 않은 전이를 만들 수 있다.
 *
 * 잠그지 않고 읽는다. 읽은 상태로 전이 여부를 정하지 않는다 — 그 판정은 원장의 fire 가 잠근 채로 한다.
 */
public interface OrderReader {

    Optional<Order> findById(UUID orderId);

    /** 공개 토큰으로 찾는다. 조회 API 는 이 값만 받는다. */
    Optional<Order> findByOrderToken(OrderToken orderToken);

    /** 예약의 주문. 예약당 주문은 평생 하나라(uq_order_preorder) 0~1건이다. */
    Optional<Order> findByPreorderId(UUID preorderId);

    /** 주문상품 하나. 주인 확인은 부르는 쪽이 그 주문으로 한다. */
    Optional<OrderItem> findItem(UUID orderItemId);

    List<OrderItem> findItems(UUID orderId);

    /**
     * 그 회원의 결제 안 된 장바구니 주문 — 결제 대기 · 승인 중이면 기한과 상관없이 전부(기한이 지났어도 아직 확보를 쥐고 있다).
     * 장바구니 주문 생성이 "회원당 하나" 를 지키는 데 쓴다 — 기한 판정({@link Order#acceptsPaymentAt})은 부르는 쪽이 한다.
     */
    List<Order> findUnpaidCartOrders(UUID customerId);

    /**
     * 기한이 지난 결제 대기 장바구니 주문 — 기한 <= now({@link Order#acceptsPaymentAt} 의 반대, 사이에 빠지는 주문이 없다). (기한, id) 순으로
     * after 다음부터 최대 limit 개 — 이어 읽기(keyset)라 처리하지 못한 주문이 앞에 남아도 그 뒤로 넘어간다. 잠그지 않는다 — 취소는 주문마다
     * 잠근 뒤 다시 판정한다. 승인 중은 고르지 않는다(결과를 기다린다).
     *
     * @param after 직전 쪽의 마지막 (기한, id). 처음이면 {@link ExpiredOrderPosition#START}
     */
    List<Order> findExpiredCartOrders(Instant now, ExpiredOrderPosition after, int limit);

    /**
     * 여러 주문의 항목을 한 번에(주문마다 다시 묻지 않는다). 주문 id · 항목 id 순이다.
     *
     * findItems(UUID) 의 오버로드로 두지 않는다 — 인자 타입만 다른 오버로드는 목 매처(any())나 null 과 만나면
     * 호출이 모호해져 컴파일이 깨진다. 이 포트의 메서드 이름은 겹치지 않게 둔다(OrderReaderContractTest).
     */
    List<OrderItem> findItemsByOrderIds(Collection<UUID> orderIds);

    /**
     * 회원의 주문, 최신순((created_at, id) 내림차순). ix_order_member_created 를 탄다.
     *
     * @param after 이 자리보다 뒤(더 오래된 것)만. 첫 페이지면 null
     * @param limit 최대 건수. 다음 페이지가 있는지 보려면 한 건 더 달라고 한다
     */
    List<Order> findByCustomer(UUID customerId, OrderPosition after, int limit);

    /** 관리자 목록, 최신순. 총계를 함께 센다. page 는 0 부터. */
    OffsetPage<Order> findForAdmin(AdminOrderFilter filter, int page, int size);

    /**
     * 이력, 번호순. upToSequence 까지만 읽는다 — 주문을 읽은 뒤 커밋된 전이의 이력이 섞이면
     * 응답의 상태와 이력의 마지막이 어긋난다(READ COMMITTED 는 SELECT 마다 스냅샷이 다르다).
     * 읽어 둔 주문의 eventSequence 를 넘긴다.
     */
    List<OrderEvent> findEvents(UUID orderId, long upToSequence);
}
