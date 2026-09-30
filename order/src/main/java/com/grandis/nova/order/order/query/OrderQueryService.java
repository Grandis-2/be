package com.grandis.nova.order.order.query;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CursorPage;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.repository.AdminOrderFilter;
import com.grandis.nova.order.order.domain.repository.OrderPosition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.OrderToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 주문 조회. 쓰기가 없어 읽기 전용 트랜잭션이고, 읽기 포트({@link OrderReader})만 쥔다.
 *
 * 목록은 주문을 먼저 읽고 항목을 주문 id 묶음으로 한 번에 읽는다(행마다 다시 묻지 않는다).
 * 사용자 목록은 커서, 관리자 목록은 오프셋이다 — 주문이 계속 들어오는 사용자 목록에서 오프셋은 항목이 밀린다.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderReader reader;

    public OrderQueryService(OrderReader reader) {
        this.reader = reader;
    }

    /** 내 주문 목록(최신순). 한 건 더 읽어 다음 페이지가 있는지 본다. */
    public CursorPage<OrderView.Summary> findMine(Long customerId, String cursor, int size) {
        List<Order> found = new ArrayList<>(reader.findByCustomer(customerId, OrderCursor.decode(cursor), size + 1));
        boolean hasNext = found.size() > size;
        if (hasNext) {
            found.removeLast();
        }
        List<OrderView.Summary> items = withItems(found);
        if (!hasNext) {
            return CursorPage.last(items);
        }
        Order last = found.getLast();
        return CursorPage.of(items, OrderCursor.encode(new OrderPosition(last.createdAt(), last.id())));
    }

    /** 내 주문 하나. 남의 주문은 존재를 알리지 않는다(404). */
    public OrderView.Detail findOne(Long customerId, String orderToken) {
        Order order = find(orderToken)
                .filter(found -> found.customerId().equals(customerId))
                .orElseThrow(OrderQueryService::notFound);
        return detail(order);
    }

    public OffsetPage<OrderView.Summary> findForAdmin(AdminOrderFilter filter, int page, int size) {
        OffsetPage<Order> found = reader.findForAdmin(filter, page, size);
        return OffsetPage.of(withItems(found.items()), found.page(), found.size(), found.total());
    }

    public OrderView.Detail findOneForAdmin(String orderToken) {
        return detail(find(orderToken).orElseThrow(OrderQueryService::notFound));
    }

    /** 형식이 틀린 토큰도 "그런 주문 없음" 이다 — 400 으로 나눠 봐야 호출자가 얻는 정보가 없다. */
    private Optional<Order> find(String orderToken) {
        OrderToken token;
        try {
            token = new OrderToken(orderToken);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return reader.findByOrderToken(token);
    }

    /** 이력은 읽은 주문의 번호까지만 — 그 뒤에 커밋된 전이가 섞이면 상태와 이력의 마지막이 어긋난다. */
    private OrderView.Detail detail(Order order) {
        return new OrderView.Detail(order, reader.findItems(order.id()),
                reader.findEvents(order.id(), order.eventSequence()));
    }

    private List<OrderView.Summary> withItems(List<Order> found) {
        Map<Long, List<OrderItem>> byOrder = reader.findItemsByOrderIds(found.stream().map(Order::id).toList()).stream()
                .collect(Collectors.groupingBy(OrderItem::orderId));
        return found.stream()
                .map(order -> new OrderView.Summary(order, byOrder.getOrDefault(order.id(), List.of())))
                .toList();
    }

    private static BusinessException notFound() {
        return new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
    }
}
