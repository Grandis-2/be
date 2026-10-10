package com.grandis.nova.order.cart;

import com.grandis.nova.order.cart.domain.model.CartOption;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogReader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * catalog 의 옵션 사실을 장바구니 판정용 {@link CartOption} 으로 바꾼다 — 장바구니 조회 · 담기와 장바구니 주문이 같은 판정을 쓴다.
 * 트랜잭션 밖에서 부른다({@link CatalogReader}).
 */
@Component
public class CartCatalog {

    static final String ACTIVE = "ACTIVE";
    static final String IN_STOCK = "IN_STOCK";

    private final CatalogReader catalog;

    public CartCatalog(CatalogReader catalog) {
        this.catalog = catalog;
    }

    /**
     * 옵션 id → 판정용 옵션. catalog 가 돌려주지 않은 옵션(판매 종료)은 빠진다.
     *
     * @throws com.grandis.nova.common.BusinessException UNAUTHENTICATED · DEPENDENCY_UNAVAILABLE ({@link CatalogReader#find})
     */
    public Map<UUID, CartOption> find(Collection<UUID> optionIds, String sessionToken) {
        Map<UUID, CartOption> options = new LinkedHashMap<>();
        catalog.find(optionIds, sessionToken).forEach((id, option) -> options.put(id, toOption(option)));
        return options;
    }

    /** 노출 = 공개 · 준비 완료, 판매 중 = 상품 · 옵션 모두 ACTIVE. 보증가는 제공할 때만(아니면 0). */
    static CartOption toOption(CatalogOption option) {
        boolean exposed = Boolean.TRUE.equals(option.visible()) && Boolean.TRUE.equals(option.registrationCompleted());
        boolean sellable = ACTIVE.equals(option.productStatus()) && ACTIVE.equals(option.optionStatus());
        boolean warrantyOffered = option.warranty() != null && option.warranty().offered();
        BigDecimal surcharge = warrantyOffered && option.warranty().surcharge() != null ? option.warranty().surcharge() : BigDecimal.ZERO;
        return new CartOption(option.optionId(), option.productId(), option.productTitle(), option.optionTitle(), option.imageUrl(),
                option.price(), IN_STOCK.equals(option.saleMode()), exposed, sellable, warrantyOffered, surcharge);
    }
}
