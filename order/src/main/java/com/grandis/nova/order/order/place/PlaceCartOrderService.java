package com.grandis.nova.order.order.place;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.cart.CartCatalog;
import com.grandis.nova.order.cart.domain.model.CartLine;
import com.grandis.nova.order.cart.domain.model.CartOption;
import com.grandis.nova.order.cart.domain.model.CartSlot;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.exception.StockShortageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 장바구니 주문 생성(source=CART). 고른 장바구니 줄로 주문을 만들고 재고를 전량 확보한다 — 하나라도 모자라면 주문을 만들지 않는다.
 *
 * 순서:
 * 1. 트랜잭션 밖: catalog 일괄 조회({@link CartCatalog}) → 살 수 없는 줄 · 가격 다름 판정과 금액 계산({@link SelectionRules}, 바로 구매와 같다).
 * 2. 트랜잭션: 그 회원의 장바구니 줄을 잠그고({@link CartStore#lockByCustomer}) 고른 줄이 모두 그대로 있는지 대조 → 아니면 409 CART_CHANGED.
 *    그 회원의 결제 안 된 장바구니 주문(결제 대기 · 승인 중 — 기한과 상관없이 전부)을 본다. 결제 안 된 장바구니 주문은 회원당 하나다(2026-10-10 결정,
 *    바로 구매 주문은 세지도 취소하지도 않는다 — 2026-10-15 결정):
 *    - 기한 안이고({@link Order#acceptsPaymentAt}) 구성(옵션마다 수량 · 보증 수량) · 단가 · 보증가 · 배송지가 모두 같은 주문이 있으면 그것을
 *      돌려준다(200, reused — 기한 연장 없음). 두 번 누른 요청이 여기로 온다.
 *    - 아니면 결제 대기인 앞 주문을 모두(구성 · 기한과 상관없이) 취소하고 새로 만든다 — 재고를 여러 주문으로 잡아 두지 못한다.
 *    - 승인 중인 앞 주문이 있으면 409 PAYMENT_IN_PROGRESS(details.orderId · status — 막는 주문) — 결제가 진행 중인 주문은 바꾸지 않고, 그 옆에
 *      새 주문도 만들지 않는다. 결과를 못 받고 멈춘 주문도 같다(돈이 나갔을 수 있다) — 결제 결과가 오거나 운영자가 풀 때까지다.
 *    새로 만들 때: 앞 주문들의 반환과 새 주문의 확보를 한 번에({@link StockLedger#releaseAndReserve}) → 주문 · 주문상품 · 첫 이력,
 *    기한 = 지금 + 10분.
 *
 * - 같은 회원의 주문 생성끼리는 장바구니 줄 잠금으로 줄 선다 — 두 번 누른 요청은 뒤의 것이 앞의 주문을 돌려받는다.
 * - 잠금 순서: 장바구니 줄 → (바꿀 앞 주문 행) → 재고 행(반환 + 확보 한 번, 잠금 순서대로). 앞 주문은 잠그지 않고 읽으므로, 그 사이 결제
 *   시작(주문 행만 잠근다)이 앞 주문을 승인 중으로 바꿀 수 있다 — 주문 행을 잠근 뒤 다시 보고 승인 중이면 409 다(새 주문 · 확보 없음, 시험으로 고정).
 * - 같은 옵션의 보증 포함 · 미포함 두 줄은 주문상품 한 줄로 합친다(수량 = 합, 보증 수량 = 보증 줄의 수량).
 * - 장바구니는 여기서 바꾸지 않는다 — 결제 성공 때 산 만큼 뺀다(명세).
 * - 교착(1213)은 새 트랜잭션에서 다시 하고(최대 {@link #MAX_ATTEMPTS}번), 끝내 교착이거나 잠금 대기 초과(1205)면 503 이다.
 * - 바깥 트랜잭션 안에서 부르면 안 된다 — catalog 호출 동안 잠금을 쥐지 않고, 다시 하기가 새 트랜잭션이어야 한다.
 */
@Service
public class PlaceCartOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceCartOrderService.class);

    static final int MAX_ATTEMPTS = 2;
    /** 새 장바구니 주문이 결제 안 된 앞 장바구니 주문을 취소할 때의 이력 사유(회원당 하나). */
    static final String REPLACED_REASON = "REPLACED_BY_NEW_ORDER";

    private final CartCatalog catalog;
    private final CartStore cart;
    private final StockLedger stock;
    private final OrderLedger ledger;
    private final OrderReader orderReader;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    public PlaceCartOrderService(CartCatalog catalog, CartStore cart, StockLedger stock, OrderLedger ledger, OrderReader orderReader,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.catalog = catalog;
        this.cart = cart;
        this.stock = stock;
        this.ledger = ledger;
        this.orderReader = orderReader;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param customerId   인증 주체의 회원 id
     * @param sessionToken 사용자가 보낸 액세스 토큰(catalog 에 그대로 전달). 로그 · 예외에 싣지 않는다
     * @param selections   고른 장바구니 줄. 비어 있지 않고 (옵션, 보증)이 겹치지 않아야 한다(요청 검증이 거른다)
     * @throws BusinessException 409 STATE_CONFLICT(살 수 없는 줄) · PRICE_CHANGED · CART_CHANGED · INSUFFICIENT_STOCK(details.variantIds) ·
     *                           PAYMENT_IN_PROGRESS(details.orderId · status — 결제 진행 중인 앞 주문),
     *                           401 · 503 · 500(catalog, {@link CartCatalog#find}), 503(잠금 대기 초과 · 다시 해도 교착)
     */
    public PlaceResult place(UUID customerId, String sessionToken, List<CartSelection> selections, PlaceOrderCommand.Address shipTo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("장바구니 주문 생성은 트랜잭션 밖에서 불러야 한다 — catalog 호출 동안 잠금을 쥐지 않고, 다시 하기가 새 트랜잭션이게");
        }
        SelectionRules.requireDistinctSlots(selections);
        Set<UUID> optionIds = selections.stream().map(CartSelection::optionId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, CartOption> options = catalog.find(optionIds, sessionToken);
        OrderDraft draft = SelectionRules.draft(customerId, OrderSource.CART, selections, options, shipTo);
        EventCause cause = EventCause.user();
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.execute(status -> placeLocked(customerId, selections, draft, cause));
            } catch (StockShortageException e) {
                throw new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK, SelectionRules.SHORTAGE_MESSAGE, Map.of("variantIds", e.optionIds()));
            } catch (PessimisticLockingFailureException e) {
                if (!MySqlLockFailures.isDeadlock(e)) {
                    log.warn("장바구니 주문 생성 잠금 대기 초과, 다시 하지 않음 customerId={}", customerId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("장바구니 주문 생성 교착 {}회, 포기 customerId={}", MAX_ATTEMPTS, customerId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                log.info("장바구니 주문 생성 교착, 다시 시도 {}/{} customerId={}", attempt, MAX_ATTEMPTS, customerId);
            }
        }
    }

    private PlaceResult placeLocked(UUID customerId, List<CartSelection> selections, OrderDraft draft, EventCause cause) {
        requireInCart(selections, cart.lockByCustomer(customerId));
        List<Order> unpaid = orderReader.findUnpaidCartOrders(customerId);
        Map<UUID, List<OrderItem>> itemsByOrder = orderReader.findItemsByOrderIds(unpaid.stream().map(Order::id).toList()).stream()
                .collect(Collectors.groupingBy(OrderItem::orderId));
        Instant now = clock.instant();
        for (Order order : unpaid) {
            List<OrderItem> items = itemsByOrder.getOrDefault(order.id(), List.of());
            // 잠그지 않고 읽었으므로 돌려주기 전에 잠가 다시 본다 — 읽은 상태 그대로일 때만 돌려준다(응답의 상태가 맞게). 그 사이 사용자 취소
            // (장바구니 잠금을 잡지 않는다)가 취소했으면 새로 만들고, 결제 시작이 승인 중으로 바꿨으면 아래에서 409 PAYMENT_IN_PROGRESS 다
            if (order.acceptsPaymentAt(now) && sameComposition(items, draft.lines()) && sameValues(order, items, draft)
                    && ledger.lockedStatus(order.id()) == order.status()) {
                return new PlaceResult(order, items, false);
            }
        }
        Map<UUID, Integer> released = new HashMap<>();
        List<UUID> canceledOrders = new ArrayList<>();
        for (Order order : unpaid) {
            // 판정은 잠근 뒤에만 한다. 승인 중이면(읽을 때 이미 그랬든, 읽은 뒤 잠그기 전에 결제 시작이 바꿨든 — 결제 시작은 주문 행만 잠가
            // 장바구니 잠금으로 막히지 않는다) 바꾸지 않고 409 PAYMENT_IN_PROGRESS 와 막는 주문을 알린다. 기한이 지났어도 같다(돈이 나갔을 수 있고,
            // 승인 호출은 기한을 넘길 수 있다 — 2026-10-10 결정). 다른 취소(만료 처리 · 사용자 취소)가 먼저 취소했으면 결제 안 된 주문이 아니므로
            // 그냥 지나간다(반환은 그쪽이 했다). 결제됨도 지나가지만, 결제 반영이 주문 행 → 장바구니 줄을 잠가 이 장바구니 잠금과 겹치면 교착 →
            // 다시 하기로 가므로 한 시도 안에서는 사실상 오지 않는 방어 갈래다.
            OrderTransition canceled = ledger.cancelUnpaidReleasingStock(order.id(), EventCause.system(REPLACED_REASON));
            if (!canceled.applied()) {
                if (canceled.status() == OrderStatus.AUTHORIZING) {
                    throw new BusinessException(OrderErrorCode.PAYMENT_IN_PROGRESS,
                            Map.of("orderId", order.id().toString(), "status", canceled.status().name()));
                }
                continue;
            }
            canceledOrders.add(order.id());
            itemsByOrder.getOrDefault(order.id(), List.of())
                    .forEach(item -> released.merge(item.line().optionId(), item.line().quantity().value(), Integer::sum));
        }
        // 반환과 확보를 옵션별 증감 하나로 합쳐 잠금 순서대로 한 번에 — 따로 돌면 재고 행 잠금 순서가 깨진다
        try {
            stock.releaseAndReserve(released, SelectionRules.quantities(draft.lines()));
        } catch (IllegalStateException e) {
            // 경보 규칙이 이 문구("재고 어긋남")로 잡는다 — 결제 반영의 판매 확정 경보(CartOrderFulfillment)와 같은 문구다. 바꾸지 않는다.
            log.error("재고 어긋남 — 앞 장바구니 주문의 확보를 반환하지 못해 새 주문을 만들지 않았다, 재고 장부 확인 필요 customerId={} orderIds={} released={}",
                    customerId, canceledOrders, released, e);
            throw new IllegalStateException("바꾸는 앞 주문의 확보를 반환하지 못했다: orderIds=" + canceledOrders, e);
        }
        Order order = ledger.place(draft, cause);
        return new PlaceResult(order, orderReader.findItems(order.id()), true);
    }

    /** 고른 줄마다 지금 장바구니에 (옵션, 보증, 수량)이 같은 줄이 있어야 한다. 장바구니의 다른 줄은 고르지 않아도 된다. */
    private static void requireInCart(List<CartSelection> selections, List<CartLine> lines) {
        Map<CartSlot, Integer> current = new HashMap<>();
        lines.forEach(line -> current.put(line.slot(), line.quantity()));
        for (CartSelection selection : selections) {
            Integer quantity = current.get(selection.slot());
            if (quantity == null || quantity != selection.quantity()) {
                throw new BusinessException(OrderErrorCode.CART_CHANGED);
            }
        }
    }

    /** 같은 구성 = 옵션마다 수량 · 보증 수량이 모두 같다. */
    private static boolean sameComposition(List<OrderItem> items, List<OrderLine> lines) {
        Map<UUID, List<Integer>> existing = items.stream().collect(Collectors.toMap(item -> item.line().optionId(),
                item -> List.of(item.line().quantity().value(), item.line().warrantyQuantity())));
        Map<UUID, List<Integer>> wanted = lines.stream().collect(Collectors.toMap(OrderLine::optionId,
                line -> List.of(line.quantity().value(), line.warrantyQuantity())));
        return existing.equals(wanted);
    }

    /** 같은 구성에 더해 단가 · 보증가(값으로 비교) · 배송지가 모두 같은가 — 사용자가 이번에 확인한 그대로의 주문인가. */
    private static boolean sameValues(Order order, List<OrderItem> items, OrderDraft draft) {
        if (!order.shipTo().equals(draft.shipTo())) {
            return false;
        }
        Map<UUID, OrderLine> wanted = draft.lines().stream().collect(Collectors.toMap(OrderLine::optionId, Function.identity()));
        for (OrderItem item : items) {
            OrderLine existing = item.line();
            OrderLine line = wanted.get(existing.optionId());
            if (existing.unitPrice().amount().compareTo(line.unitPrice().amount()) != 0
                    || existing.warrantyUnitPrice().amount().compareTo(line.warrantyUnitPrice().amount()) != 0) {
                return false;
            }
        }
        return true;
    }
}
