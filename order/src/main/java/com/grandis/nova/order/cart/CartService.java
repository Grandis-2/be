package com.grandis.nova.order.cart;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.cart.domain.exception.CartSlotTakenException;
import com.grandis.nova.order.cart.domain.model.CartLimits;
import com.grandis.nova.order.cart.domain.model.CartLine;
import com.grandis.nova.order.cart.domain.model.CartOption;
import com.grandis.nova.order.cart.domain.model.Unavailability;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogReader;
import com.grandis.nova.order.stock.domain.model.StockLevel;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.web.ValidationFailures;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 장바구니(명세 F-U-07). 상품 정보는 catalog 에 묻고(트랜잭션 밖), 재고는 order 의 재고 표를 읽기만 한다 — 담기 · 수정은 가용 재고를 넘지 않는지
 * 확인할 뿐 확보하지 않는다. 가격은 저장하지 않고 조회 때 catalog 의 지금 값을 쓴다.
 *
 * 담기는 그 회원의 줄을 모두 잠근 뒤(FOR UPDATE) 합산 · 줄 수를 판정한다 — 겹친 담기가 51번째 줄 · 99 초과를 만들지 않는다(MySQL 8.4 · READ COMMITTED
 * 실측: 49줄에서 겹친 새 줄 담기 둘 중 뒤의 것은 앞의 커밋을 기다렸다가 50줄을 본다). 비어 있는 장바구니는 잠글 행이 없어 같은 옵션 담기가
 * 겹치면 유일 키가 잡고({@link CartSlotTakenException}), 새 트랜잭션에서 한 번 더 하면 그 줄에 합산된다.
 */
@Service
public class CartService {

    /** 겹친 첫 담기를 다시 하는 횟수. 두 번째 시도는 줄이 있으므로 잠금으로 줄 선다. */
    static final int ADD_ATTEMPTS = 2;
    static final String ACTIVE = "ACTIVE";
    static final String IN_STOCK = "IN_STOCK";

    private final CartStore store;
    private final StockReader stock;
    private final CatalogReader catalog;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CartService(CartStore store, StockReader stock, CatalogReader catalog, PlatformTransactionManager transactionManager,
                       Clock clock) {
        this.store = store;
        this.stock = stock;
        this.catalog = catalog;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 내 장바구니. 살 수 없는 줄도 그대로 싣고 이유를 단다. 비어 있으면 catalog 를 부르지 않는다. */
    public CartView view(UUID customerId, String sessionToken) {
        requireNoTransaction();
        List<CartLine> lines = transaction.execute(status -> store.findByCustomer(customerId));
        if (lines.isEmpty()) {
            return new CartView(List.of());
        }
        Set<UUID> optionIds = new LinkedHashSet<>();
        lines.forEach(line -> optionIds.add(line.optionId()));
        Map<UUID, CatalogOption> options = catalog.find(optionIds, sessionToken);
        Map<UUID, Integer> available = availability(optionIds);

        List<CartView.Line> views = new ArrayList<>();
        for (CartLine line : lines) {
            CartOption option = Optional.ofNullable(options.get(line.optionId())).map(CartService::toOption).orElse(null);
            int availableQuantity = available.getOrDefault(line.optionId(), 0);
            Unavailability reason = Unavailability.of(option, line.warranty(), line.quantity(), availableQuantity);
            views.add(toView(line, option, availableQuantity, reason));
        }
        return new CartView(views);
    }

    /** 헤더 배지 — 줄 수. */
    public long count(UUID customerId) {
        return transaction.execute(status -> store.countByCustomer(customerId));
    }

    /**
     * 담기. 같은 (옵션, 보증)이 있으면 그 줄에 수량을 더한다.
     *
     * @throws BusinessException 404 NOT_FOUND — 없는 옵션 · 회원에게 보이지 않는 상품(비공개 · 준비 전).
     *                           409 PREORDER_NOT_CARTABLE · STATE_CONFLICT(판매 중지) · INSUFFICIENT_STOCK(합산이 가용 재고 초과).
     *                           400 VALIDATION_FAILED — 보증을 주지 않는 상품에 보증, 합산 99 초과, 51번째 줄
     */
    public CartLine add(UUID customerId, String sessionToken, UUID optionId, int quantity, boolean warranty) {
        requireNoTransaction();
        CartLimits.requireQuantity(quantity);
        CatalogOption found = catalog.find(List.of(optionId), sessionToken).get(optionId);
        CartOption option = found == null ? null : toOption(found);
        requireCartable(option, warranty);
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> addLocked(customerId, optionId, quantity, warranty));
            } catch (CartSlotTakenException e) {
                if (attempt >= ADD_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /**
     * 수량을 절대값으로 바꾼다(1~99). 옵션 · 보증 교체는 삭제 후 담기다(명세). 판매 상태는 보지 않는다 — 조회가 살 수 없음을 알린다.
     *
     * @throws BusinessException 404 NOT_FOUND — 없는 줄 · 남의 줄. 409 INSUFFICIENT_STOCK — 가용 재고 초과
     */
    public CartLine changeQuantity(UUID customerId, UUID lineId, int quantity) {
        CartLimits.requireQuantity(quantity);
        return transaction.execute(status -> {
            CartLine line = store.lockLine(customerId, lineId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
            requireAvailable(line.optionId(), quantity);
            store.changeQuantity(line.id(), quantity, clock.instant());
            return new CartLine(line.id(), customerId, line.optionId(), line.warranty(), quantity);
        });
    }

    /** @throws BusinessException 404 NOT_FOUND — 없는 줄 · 남의 줄 */
    public void remove(UUID customerId, UUID lineId) {
        Integer deleted = transaction.execute(status -> store.delete(customerId, lineId));
        if (deleted == null || deleted == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }

    private CartLine addLocked(UUID customerId, UUID optionId, int quantity, boolean warranty) {
        List<CartLine> lines = store.lockByCustomer(customerId);
        Optional<CartLine> existing = lines.stream().filter(line -> line.sameSlot(optionId, warranty)).findFirst();
        int next = existing.map(line -> line.quantity() + quantity).orElse(quantity);
        if (next > CartLimits.MAX_QUANTITY) {
            throw ValidationFailures.of("quantity", "한 줄에 담을 수 있는 수량은 최대 %d개입니다.".formatted(CartLimits.MAX_QUANTITY));
        }
        if (existing.isEmpty() && lines.size() >= CartLimits.MAX_LINES) {
            throw ValidationFailures.of("variantId", "장바구니에는 최대 %d줄까지 담을 수 있습니다.".formatted(CartLimits.MAX_LINES));
        }
        requireAvailable(optionId, next);
        if (existing.isPresent()) {
            CartLine line = existing.get();
            store.changeQuantity(line.id(), next, clock.instant());
            return new CartLine(line.id(), customerId, optionId, warranty, next);
        }
        return store.insert(customerId, optionId, warranty, quantity);
    }

    private static void requireCartable(CartOption option, boolean warranty) {
        if (option == null || !option.exposed()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        if (!option.inStock()) {
            throw new BusinessException(OrderErrorCode.PREORDER_NOT_CARTABLE);
        }
        if (!option.sellableStatus()) {
            throw new BusinessException(OrderErrorCode.STATE_CONFLICT);
        }
        if (warranty && !option.warrantyOffered()) {
            throw ValidationFailures.of("warranty", "보증을 제공하지 않는 상품입니다.");
        }
    }

    private void requireAvailable(UUID optionId, int quantity) {
        int available = availability(List.of(optionId)).getOrDefault(optionId, 0);
        if (quantity > available) {
            throw new BusinessException(OrderErrorCode.INSUFFICIENT_STOCK);
        }
    }

    /** 옵션별 가용 재고(총량 − 확보 − 판매). 재고 행이 없는 옵션은 빠진다(호출자가 0 으로 읽는다). */
    private Map<UUID, Integer> availability(java.util.Collection<UUID> optionIds) {
        Map<UUID, Integer> available = new HashMap<>();
        for (StockLevel level : stock.findByOptionIds(optionIds)) {
            available.put(level.optionId(), level.available());
        }
        return available;
    }

    private static CartOption toOption(CatalogOption option) {
        boolean exposed = Boolean.TRUE.equals(option.visible()) && Boolean.TRUE.equals(option.registrationCompleted());
        boolean sellable = ACTIVE.equals(option.productStatus()) && ACTIVE.equals(option.optionStatus());
        boolean warrantyOffered = option.warranty() != null && option.warranty().offered();
        BigDecimal surcharge = warrantyOffered && option.warranty().surcharge() != null ? option.warranty().surcharge() : BigDecimal.ZERO;
        return new CartOption(option.optionId(), option.productId(), option.productTitle(), option.optionTitle(), option.imageUrl(),
                option.price(), IN_STOCK.equals(option.saleMode()), exposed, sellable, warrantyOffered, surcharge);
    }

    private static CartView.Line toView(CartLine line, CartOption option, int available, Unavailability reason) {
        if (option == null) {
            return new CartView.Line(line.id(), line.optionId(), null, null, null, null, line.warranty(), line.quantity(),
                    null, null, null, available, reason);
        }
        BigDecimal warrantyPrice = line.warranty() && option.warrantyOffered() ? option.warrantySurcharge() : BigDecimal.ZERO;
        BigDecimal lineAmount = option.price().add(warrantyPrice).multiply(BigDecimal.valueOf(line.quantity()));
        return new CartView.Line(line.id(), line.optionId(), option.productId(), option.productTitle(), option.optionTitle(),
                option.imageUrl(), line.warranty(), line.quantity(), option.price(), warrantyPrice, lineAmount, available, reason);
    }

    /** catalog 호출이 트랜잭션 안에 들어가면 그 응답 시간만큼 잠금을 쥔다 — 바깥 트랜잭션에서 부르지 않는다. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("장바구니 조회 · 담기는 트랜잭션 밖에서 불러야 한다 — catalog 호출 동안 잠금을 쥐지 않게");
        }
    }
}
