package com.grandis.nova.order.order.place;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.cart.CartCatalog;
import com.grandis.nova.order.cart.domain.model.CartLine;
import com.grandis.nova.order.cart.domain.model.CartOption;
import com.grandis.nova.order.cart.domain.model.Unavailability;
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
import com.grandis.nova.order.web.ValidationFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 1. 트랜잭션 밖: catalog 일괄 조회({@link CartCatalog}). 살 수 없는 줄이 있으면 409 STATE_CONFLICT(details.items 에 줄마다 이유),
 *    화면에서 본 단가 · 보증가가 지금 값과 다르면 409 PRICE_CHANGED(details.items 에 지금 값). 금액은 catalog 의 지금 값으로 계산한다.
 * 2. 트랜잭션: 그 회원의 장바구니 줄을 잠그고({@link CartStore#lockByCustomer}) 고른 줄이 모두 그대로 있는지 대조 → 아니면 409 CART_CHANGED.
 *    같은 구성(옵션마다 수량 · 보증 수량)의 아직 유효한 장바구니 주문(결제 대기 · 승인 중, 기한 전)을 본다(2026-10-10 결정):
 *    - 단가 · 보증가 · 배송지까지 같으면 그 주문을 돌려준다(200, reused — 기한 연장 없음). 두 번 누른 요청이 여기로 온다.
 *    - 값이 다르고 결제 대기면 그 주문을 취소 · 재고 반환하고 새로 만든다 — 같은 구성의 결제 대기 주문은 늘 하나다.
 *    - 값이 다르고 승인 중이면 409 STATE_CONFLICT — 결제가 진행 중인 주문은 바꾸지 않는다.
 *    새로 만들 때: 재고 확보({@link StockLedger#reserve}) → 주문 · 주문상품 · 첫 이력, 기한 = 지금 + 10분.
 *
 * - 같은 회원의 주문 생성끼리는 장바구니 줄 잠금으로 줄 선다 — 두 번 누른 요청은 뒤의 것이 앞의 주문을 돌려받는다.
 * - 잠금 순서: 장바구니 줄 → (바꿀 앞 주문 행) → 재고 행. 열린 주문은 잠그지 않고 읽으므로, 그 사이 결제 시작(주문 행만 잠근다)이
 *   앞 주문을 승인 중으로 바꿀 수 있다 — 주문 행을 잠근 뒤 다시 보고 승인 중이면 409 다(새 주문 · 확보 없음, 시험으로 고정).
 * - 같은 옵션의 보증 포함 · 미포함 두 줄은 주문상품 한 줄로 합친다(수량 = 합, 보증 수량 = 보증 줄의 수량).
 * - 장바구니는 여기서 바꾸지 않는다 — 결제 성공 때 산 만큼 뺀다(명세).
 * - 교착(1213)은 새 트랜잭션에서 다시 하고(최대 {@link #MAX_ATTEMPTS}번), 끝내 교착이거나 잠금 대기 초과(1205)면 503 이다.
 * - 바깥 트랜잭션 안에서 부르면 안 된다 — catalog 호출 동안 잠금을 쥐지 않고, 다시 하기가 새 트랜잭션이어야 한다.
 */
@Service
public class PlaceCartOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceCartOrderService.class);

    static final int MAX_ATTEMPTS = 2;
    /** 값이 다른 같은 구성의 새 주문이 앞 주문을 바꿀 때의 이력 사유. */
    static final String REPLACED_REASON = "REPLACED_BY_NEW_ORDER";
    /** 주문의 재고 부족 문구(명세 POST /orders). 같은 코드의 기본 문구는 장바구니 담기의 것이다. */
    static final String SHORTAGE_MESSAGE = "주문 전체 수량을 확보할 수 없습니다.";
    /** 주문 금액 상한(orders.total_amount decimal(12,0)). */
    static final BigDecimal MAX_TOTAL = new BigDecimal("999999999999");

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
     * @throws BusinessException 409 STATE_CONFLICT(살 수 없는 줄) · PRICE_CHANGED · CART_CHANGED · INSUFFICIENT_STOCK(details.variantIds),
     *                           401 · 503 · 500(catalog, {@link CartCatalog#find}), 503(잠금 대기 초과 · 다시 해도 교착)
     */
    public PlaceResult place(UUID customerId, String sessionToken, List<CartSelection> selections, PlaceOrderCommand.Address shipTo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("장바구니 주문 생성은 트랜잭션 밖에서 불러야 한다 — catalog 호출 동안 잠금을 쥐지 않고, 다시 하기가 새 트랜잭션이게");
        }
        requireDistinctSlots(selections);
        Set<UUID> optionIds = selections.stream().map(CartSelection::optionId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, CartOption> options = catalog.find(optionIds, sessionToken);
        requirePurchasable(selections, options);
        requireSamePrices(selections, options);

        // 입력 검증 · 금액 계산은 트랜잭션 전에 끝낸다. 원장 안에서 던지면 트랜잭션이 rollback-only 가 된다.
        PlaceOrderCommand command = toCommand(customerId, selections, options, shipTo);
        requireWithinTotalLimit(command);
        OrderDraft draft = command.toDraft();
        EventCause cause = EventCause.user();
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.execute(status -> placeLocked(customerId, selections, draft, cause));
            } catch (StockShortageException e) {
                throw new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK, SHORTAGE_MESSAGE, Map.of("variantIds", e.optionIds()));
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
        List<Order> open = orderReader.findOpenCartOrders(customerId, clock.instant());
        Map<UUID, List<OrderItem>> itemsByOrder = orderReader.findItemsByOrderIds(open.stream().map(Order::id).toList()).stream()
                .collect(Collectors.groupingBy(OrderItem::orderId));
        List<Order> replaced = new ArrayList<>();
        for (Order order : open) {
            List<OrderItem> items = itemsByOrder.getOrDefault(order.id(), List.of());
            if (!sameComposition(items, draft.lines())) {
                continue;
            }
            if (sameValues(order, items, draft)) {
                return new PlaceResult(order, items, false);
            }
            if (order.status() != OrderStatus.AWAITING_PAYMENT) {
                throw new BusinessException(OrderErrorCode.STATE_CONFLICT);
            }
            replaced.add(order);
        }
        for (Order order : replaced) {
            // 읽을 때는 결제 대기였어도 잠그고 보면 승인 중일 수 있다(결제 시작은 주문 행만 잠가 장바구니 잠금으로 막히지 않는다).
            // 그러면 바꾸지 않고 409 — 만료로 이미 취소된 주문만 그냥 지나간다(반환도 이미 했다).
            OrderTransition canceled = ledger.cancelUnpaidReleasingStock(order.id(), EventCause.system(REPLACED_REASON));
            if (!canceled.applied()) {
                if (canceled.status() != OrderStatus.CANCELED) {
                    throw new BusinessException(OrderErrorCode.STATE_CONFLICT);
                }
                continue;
            }
            try {
                stock.release(quantities(itemsByOrder.get(order.id()).stream().map(OrderItem::line).toList()));
            } catch (IllegalStateException e) {
                throw new IllegalStateException("바꾸는 앞 주문의 확보를 반환하지 못했다: orderId=" + order.id(), e);
            }
        }
        stock.reserve(quantities(draft.lines()));
        Order order = ledger.place(draft, cause);
        return new PlaceResult(order, orderReader.findItems(order.id()), true);
    }

    private static Map<UUID, Integer> quantities(List<OrderLine> lines) {
        return lines.stream().collect(Collectors.toMap(OrderLine::optionId, line -> line.quantity().value()));
    }

    /** 금액 칸(12자리)을 넘는 주문은 만들 수 없다 — 400(그대로 두면 Money 가 IllegalArgumentException 으로 500). */
    private static void requireWithinTotalLimit(PlaceOrderCommand command) {
        BigDecimal total = BigDecimal.ZERO;
        for (PlaceOrderCommand.Line line : command.lines()) {
            total = total.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                    .add(line.warrantyUnitPrice().multiply(BigDecimal.valueOf(line.warrantyQuantity())));
        }
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw ValidationFailures.of("items", "한 번에 주문할 수 있는 금액을 넘었습니다.");
        }
    }

    /** 같은 (옵션, 보증)을 두 번 고르면 어느 수량인지 모른다 — 400. */
    private static void requireDistinctSlots(List<CartSelection> selections) {
        Set<String> seen = new LinkedHashSet<>();
        for (CartSelection selection : selections) {
            if (!seen.add(selection.optionId() + "/" + selection.warranty())) {
                throw ValidationFailures.of("items", "같은 옵션 · 보증 줄을 두 번 고를 수 없습니다.");
            }
        }
    }

    /** 살 수 있는가는 장바구니 조회와 같은 판정이다. 재고는 여기서 보지 않는다 — 확보가 조건부 UPDATE 로 가른다. */
    private static void requirePurchasable(List<CartSelection> selections, Map<UUID, CartOption> options) {
        List<Map<String, Object>> unavailable = new ArrayList<>();
        for (CartSelection selection : selections) {
            Unavailability reason = Unavailability.of(options.get(selection.optionId()), selection.warranty(), selection.quantity(),
                    Integer.MAX_VALUE);
            if (reason != null) {
                unavailable.add(Map.of("variantId", selection.optionId(), "warranty", selection.warranty(), "reason", reason.name()));
            }
        }
        if (!unavailable.isEmpty()) {
            throw new BusinessException(OrderErrorCode.STATE_CONFLICT, Map.of("items", unavailable));
        }
    }

    /** 화면에서 본 값과 지금 값이 다르면 주문하지 않고 지금 값을 알린다. 비교는 값으로(scale 무시). */
    private static void requireSamePrices(List<CartSelection> selections, Map<UUID, CartOption> options) {
        List<Map<String, Object>> changed = new ArrayList<>();
        for (CartSelection selection : selections) {
            CartOption option = options.get(selection.optionId());
            boolean unitChanged = selection.expectedUnitPrice().compareTo(option.price()) != 0;
            boolean warrantyChanged = selection.warranty()
                    && (selection.expectedWarrantyPrice() == null || selection.expectedWarrantyPrice().compareTo(option.warrantySurcharge()) != 0);
            if (unitChanged || warrantyChanged) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("variantId", selection.optionId());
                item.put("warranty", selection.warranty());
                item.put("unitPrice", option.price());
                item.put("warrantyPrice", selection.warranty() ? option.warrantySurcharge() : BigDecimal.ZERO);
                changed.add(item);
            }
        }
        if (!changed.isEmpty()) {
            throw new BusinessException(OrderErrorCode.PRICE_CHANGED, Map.of("items", changed));
        }
    }

    /** 고른 줄마다 지금 장바구니에 (옵션, 보증, 수량)이 같은 줄이 있어야 한다. 장바구니의 다른 줄은 고르지 않아도 된다. */
    private static void requireInCart(List<CartSelection> selections, List<CartLine> lines) {
        Map<String, Integer> current = new HashMap<>();
        lines.forEach(line -> current.put(line.optionId() + "/" + line.warranty(), line.quantity()));
        for (CartSelection selection : selections) {
            Integer quantity = current.get(selection.optionId() + "/" + selection.warranty());
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

    /** 옵션마다 한 줄 — 보증 포함 · 미포함 줄을 합친다. 이름 · 단가 · 보증가는 catalog 의 지금 값이다. */
    private static PlaceOrderCommand toCommand(UUID customerId, List<CartSelection> selections, Map<UUID, CartOption> options,
                                               PlaceOrderCommand.Address shipTo) {
        Map<UUID, int[]> counts = new LinkedHashMap<>();   // [수량, 보증 수량]
        for (CartSelection selection : selections) {
            int[] count = counts.computeIfAbsent(selection.optionId(), id -> new int[2]);
            count[0] += selection.quantity();
            if (selection.warranty()) {
                count[1] += selection.quantity();
            }
        }
        List<PlaceOrderCommand.Line> lines = new ArrayList<>();
        counts.forEach((optionId, count) -> {
            CartOption option = options.get(optionId);
            lines.add(new PlaceOrderCommand.Line(option.productId(), optionId, count[0], option.price(), count[1],
                    count[1] == 0 ? BigDecimal.ZERO : option.warrantySurcharge(), option.productTitle(), option.optionTitle()));
        });
        return new PlaceOrderCommand(customerId, OrderSource.CART, null, null, shipTo, lines);
    }
}
