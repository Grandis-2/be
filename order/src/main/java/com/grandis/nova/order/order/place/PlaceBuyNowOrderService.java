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
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 바로 구매 주문 생성(source=BUY_NOW). 상세페이지에서 고른 옵션 하나로 주문을 만들고 재고를 확보한다.
 *
 * 요청: items 는 옵션 하나의 보증 포함 · 미포함 최대 두 줄 — 옵션이 둘 이상이거나 같은 (옵션, 보증) 줄이 두 번이면 400, preorderId 가 오면 400.
 * 두 줄은 주문상품 한 줄로 합친다(수량 = 합, 보증 수량 = 보증 줄의 수량).
 *
 * 순서: 트랜잭션 밖 catalog 조회 → 살 수 없음 409 STATE_CONFLICT · 가격 다름 409 PRICE_CHANGED · 금액 계산({@link SelectionRules}, 장바구니 주문과
 * 같다) → 트랜잭션: 재고 확보({@link StockLedger#reserve}, 모자라면 409 INSUFFICIENT_STOCK) → 주문 · 주문상품 · 첫 이력, 기한 = 지금 + 10분.
 *
 * - 장바구니와 무관하다(2026-10-15 결정) — 장바구니를 읽지도 빼지도 않고, 장바구니 주문의 "회원당 미결제 하나" 에도 들지 않는다.
 *   그래서 CART_CHANGED · PAYMENT_IN_PROGRESS 가 없다.
 * - 누를 때마다 새 주문이다(201, reused=false — 재사용 · 서버 멱등 없음). 두 번 누름은 화면이 버튼을 잠가 막고, 남은 미결제는 기한 만료가 재고를 돌려준다.
 * - 교착(1213)은 새 트랜잭션에서 다시 하고(최대 {@link #MAX_ATTEMPTS}번), 끝내 교착이거나 잠금 대기 초과(1205)면 503 이다 — 장바구니 주문과 같다.
 * - 바깥 트랜잭션 안에서 부르면 안 된다 — catalog 호출 동안 잠금을 쥐지 않고, 다시 하기가 새 트랜잭션이어야 한다.
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

    public PlaceBuyNowOrderService(CartCatalog catalog, StockLedger stock, OrderLedger ledger, OrderReader orderReader,
                                   PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.stock = stock;
        this.ledger = ledger;
        this.orderReader = orderReader;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param customerId   인증 주체의 회원 id
     * @param sessionToken 사용자가 보낸 액세스 토큰(catalog 에 그대로 전달). 로그 · 예외에 싣지 않는다
     * @param selections   고른 줄. 비어 있지 않아야 한다(요청 검증이 거른다)
     * @throws BusinessException 400(옵션이 둘 이상 · 같은 줄 두 번 · 금액 칸을 넘음), 409 STATE_CONFLICT · PRICE_CHANGED ·
     *                           INSUFFICIENT_STOCK(details.variantIds), 401 · 503 · 500(catalog, {@link CartCatalog#find}),
     *                           503(잠금 대기 초과 · 다시 해도 교착)
     */
    public PlaceResult place(UUID customerId, String sessionToken, List<CartSelection> selections, PlaceOrderCommand.Address shipTo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("바로 구매 주문 생성은 트랜잭션 밖에서 불러야 한다 — catalog 호출 동안 잠금을 쥐지 않고, 다시 하기가 새 트랜잭션이게");
        }
        Set<UUID> optionIds = selections.stream().map(CartSelection::optionId).collect(Collectors.toSet());
        if (optionIds.size() != 1) {
            throw ValidationFailures.of("items", "바로 구매는 옵션 하나만 주문할 수 있습니다.");
        }
        SelectionRules.requireDistinctSlots(selections);
        Map<UUID, CartOption> options = catalog.find(optionIds, sessionToken);
        OrderDraft draft = SelectionRules.draft(customerId, OrderSource.BUY_NOW, selections, options, shipTo);
        EventCause cause = EventCause.user();
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.execute(status -> {
                    stock.reserve(SelectionRules.quantities(draft.lines()));
                    Order order = ledger.place(draft, cause);
                    return new PlaceResult(order, orderReader.findItems(order.id()), true);
                });
            } catch (StockShortageException e) {
                throw new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK, SelectionRules.SHORTAGE_MESSAGE, Map.of("variantIds", e.optionIds()));
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
}
