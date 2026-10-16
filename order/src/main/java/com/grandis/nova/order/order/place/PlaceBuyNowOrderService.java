package com.grandis.nova.order.order.place;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.cart.CartCatalog;
import com.grandis.nova.order.cart.domain.model.CartOption;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.exception.OrderKeyTakenException;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.ShipTo;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 바로 구매 주문 생성(source=BUY_NOW). 상세페이지에서 고른 옵션 하나로 주문을 만들고 재고를 확보한다.
 *
 * 요청: items 는 옵션 하나의 보증 포함 · 미포함 최대 두 줄 — 옵션이 둘 이상이거나 같은 (옵션, 보증) 줄이 두 번이면 400, preorderId 가 오면 400.
 * 두 줄은 주문상품 한 줄로 합친다(수량 = 합, 보증 수량 = 보증 줄의 수량). Idempotency-Key(UUID)는 화면이 누름마다 하나 만들고 재전송에 같은 키를 싣는다.
 *
 * 순서: 같은 키의 주문을 잠그지 않고 찾아 있으면 바로 대조 → 트랜잭션 밖 catalog 조회 → 살 수 없음 409 STATE_CONFLICT · 가격 다름 409 PRICE_CHANGED ·
 * 금액 계산({@link SelectionRules}, 장바구니 주문과 같다) → 트랜잭션: 재고 확보({@link StockLedger#reserve}) → 주문(키 포함) · 주문상품 · 첫 이력,
 * 기한 = 지금 + 10분(201).
 *
 * 같은 키 — "있는지 보고 넣기" 가 아니라 유일 키(uq_order_idempotency)로 가른다. 앞의 찾기는 catalog 호출을 아끼는 지름길일 뿐이다.
 * - 넣다가 중복이면 그 트랜잭션(확보 포함)은 롤백되고, 새 트랜잭션에서 그 키의 주문을 읽어 대조한다: 옵션 · 수량 · 보증 수량 · 배송지가 같으면
 *   그 주문을 지금 상태 그대로 돌려주고(200, reused — 장바구니 재사용과 같은 모양), 다르면 422 KEY_PAYLOAD_MISMATCH. 가격은 대조하지 않는다.
 * - 동시에 온 같은 키는 뒤의 것이 앞의 것의 재고 행 잠금(또는 유일 키)에서 기다렸다가, 앞의 것이 커밋하면 중복이나 재고 부족으로 끝나 위처럼
 *   대조한다(재고 부족이어도 그 키의 주문이 있으면 그것이다). 앞의 것이 롤백했으면 뒤의 것이 새로 만든다.
 * - 키가 다르면 새 주문이다. 장바구니와는 무관하다(2026-10-15 결정) — 장바구니를 읽지도 빼지도 않고 "회원당 미결제 하나" 에도 들지 않아
 *   CART_CHANGED · PAYMENT_IN_PROGRESS 가 없다.
 * - 교착(1213)은 새 트랜잭션에서 다시 하고(최대 {@link #MAX_ATTEMPTS}번), 끝내 교착이거나 잠금 대기 초과(1205)면 503 이다 — 장바구니 주문과 같다.
 * - 바깥 트랜잭션 안에서 부르면 안 된다 — catalog 호출 동안 잠금을 쥐지 않고, 다시 하기 · 중복 뒤 읽기가 새 트랜잭션이어야 한다.
 */
@Service
public class PlaceBuyNowOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceBuyNowOrderService.class);

    static final int MAX_ATTEMPTS = 2;

    private final CartCatalog catalog;
    private final StockLedger stock;
    private final OrderLedger ledger;
    private final OrderReader orderReader;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public PlaceBuyNowOrderService(CartCatalog catalog, StockLedger stock, OrderLedger ledger, OrderReader orderReader,
                                   PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.stock = stock;
        this.ledger = ledger;
        this.orderReader = orderReader;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * @param customerId     인증 주체의 회원 id
     * @param sessionToken   사용자가 보낸 액세스 토큰(catalog 에 그대로 전달). 로그 · 예외에 싣지 않는다
     * @param idempotencyKey 요청의 Idempotency-Key — 같은 회원 · 같은 키는 한 주문이다
     * @param selections     고른 줄. 비어 있지 않아야 한다(요청 검증이 거른다)
     * @throws BusinessException 400(옵션이 둘 이상 · 같은 줄 두 번 · 금액 칸을 넘음), 409 STATE_CONFLICT · PRICE_CHANGED ·
     *                           INSUFFICIENT_STOCK(details.variantIds), 422 KEY_PAYLOAD_MISMATCH, 401 · 503 · 500(catalog,
     *                           {@link CartCatalog#find}), 503(잠금 대기 초과 · 다시 해도 교착)
     */
    public PlaceResult place(UUID customerId, String sessionToken, UUID idempotencyKey, List<CartSelection> selections,
                             PlaceOrderCommand.Address shipTo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("바로 구매 주문 생성은 트랜잭션 밖에서 불러야 한다 — catalog 호출 동안 잠금을 쥐지 않고, 다시 하기가 새 트랜잭션이게");
        }
        Set<UUID> optionIds = selections.stream().map(CartSelection::optionId).collect(Collectors.toSet());
        if (optionIds.size() != 1) {
            throw ValidationFailures.of("items", "바로 구매는 옵션 하나만 주문할 수 있습니다.");
        }
        SelectionRules.requireDistinctSlots(selections);
        Requested requested = Requested.of(selections, shipTo);
        Optional<PlaceResult> replayed = replay(customerId, idempotencyKey, requested);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        Map<UUID, CartOption> options = catalog.find(optionIds, sessionToken);
        OrderDraft draft = SelectionRules.draft(customerId, OrderSource.BUY_NOW, idempotencyKey, selections, options, shipTo);
        EventCause cause = EventCause.user();
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.execute(status -> {
                    stock.reserve(SelectionRules.quantities(draft.lines()));
                    Order order = ledger.place(draft, cause);
                    return new PlaceResult(order, orderReader.findItems(order.id()), true);
                });
            } catch (OrderKeyTakenException e) {
                // 같은 키가 먼저 들어갔다. 이 트랜잭션(확보 포함)은 롤백됐으므로 새 트랜잭션에서 그 주문을 읽어 대조한다
                return replay(customerId, idempotencyKey, requested).orElseThrow(() ->
                        new IllegalStateException("멱등 키 중복인데 그 키의 주문이 없다: customerId=" + customerId, e));
            } catch (StockShortageException e) {
                // 같은 키의 앞 요청이 마지막 재고를 가져갔을 수 있다 — 그 주문이 있으면 그것이다
                return replay(customerId, idempotencyKey, requested).orElseThrow(() -> new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK,
                        SelectionRules.SHORTAGE_MESSAGE, Map.of("variantIds", e.optionIds())));
            } catch (PessimisticLockingFailureException e) {
                if (!MySqlLockFailures.isDeadlock(e)) {
                    log.warn("바로 구매 주문 생성 잠금 대기 초과, 다시 하지 않음 customerId={}", customerId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("바로 구매 주문 생성 교착 {}회, 포기 customerId={}", MAX_ATTEMPTS, customerId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                log.info("바로 구매 주문 생성 교착, 다시 시도 {}/{} customerId={}", attempt, MAX_ATTEMPTS, customerId);
            }
        }
    }

    /** 그 키의 주문이 있으면 대조한다 — 같은 요청이면 그 주문(reused), 다르면 422. 없으면 비어 있다. */
    private Optional<PlaceResult> replay(UUID customerId, UUID idempotencyKey, Requested requested) {
        Optional<PlaceResult> existing = readTransaction.execute(status -> orderReader.findByIdempotencyKey(customerId, idempotencyKey)
                .map(order -> new PlaceResult(order, orderReader.findItems(order.id()), false)));
        if (existing.isPresent() && !requested.matches(existing.get())) {
            throw new BusinessException(OrderErrorCode.KEY_PAYLOAD_MISMATCH);
        }
        return existing;
    }

    /** 같은 요청인가를 가르는 값 — 합친 한 줄(옵션 · 수량 · 보증 수량)과 배송지. 가격은 넣지 않는다(그사이 바뀌었어도 같은 요청의 재전송이다). */
    private record Requested(UUID optionId, int quantity, int warrantyQuantity, ShipTo shipTo) {

        static Requested of(List<CartSelection> selections, PlaceOrderCommand.Address shipTo) {
            int quantity = selections.stream().mapToInt(CartSelection::quantity).sum();
            int warrantyQuantity = selections.stream().filter(CartSelection::warranty).mapToInt(CartSelection::quantity).sum();
            return new Requested(selections.getFirst().optionId(), quantity, warrantyQuantity,
                    new ShipTo(shipTo.name(), shipTo.phone(), shipTo.postalCode(), shipTo.line1(), shipTo.line2()));
        }

        boolean matches(PlaceResult existing) {
            List<OrderItem> items = existing.items();
            if (items.size() != 1 || !existing.order().shipTo().equals(shipTo)) {
                return false;
            }
            OrderLine line = items.getFirst().line();
            return line.optionId().equals(optionId) && line.quantity().value() == quantity && line.warrantyQuantity() == warrantyQuantity;
        }
    }
}
