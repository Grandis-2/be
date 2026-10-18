package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.integration.StockReader;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 목록 카드의 품절을 order 재고로 채운다 — 일반 상품에서 판매 중 옵션 중 남은 수량이 1 이상인 것이 없으면 품절(재고 행이 없는 옵션은 판매 불가).
 * 쪽을 고른 뒤 그 쪽의 상품만 묻는다(페이징 조건이 아니라 표시 칸이다). DB 트랜잭션 밖에서 부른다.
 * order 가 답하지 않으면 품절 표시만 뺀다(soldOut=false, 2026-10-19 결정) — 목록은 뜨고, 주문은 어차피 order 가 다시 판정한다.
 */
@Component
public class SoldOutMarker {

    private static final Logger log = LoggerFactory.getLogger(SoldOutMarker.class);

    private final ProductListingQueryRepository listings;
    private final StockReader stocks;

    public SoldOutMarker(ProductListingQueryRepository listings, StockReader stocks) {
        this.listings = listings;
        this.stocks = stocks;
    }

    public List<ProductListItem> mark(List<ProductListItem> items) {
        Set<UUID> soldOut = soldOut(items.stream().filter(item -> item.saleMode() == SaleMode.IN_STOCK).map(ProductListItem::productId).toList());
        return items.stream().map(item -> item.withSoldOut(soldOut.contains(item.productId()))).toList();
    }

    public List<AdminProductListItem> markAdmin(List<AdminProductListItem> items) {
        Set<UUID> soldOut = soldOut(items.stream().filter(item -> item.saleMode() == SaleMode.IN_STOCK).map(AdminProductListItem::productId).toList());
        return items.stream().map(item -> item.withSoldOut(soldOut.contains(item.productId()))).toList();
    }

    /** @return 품절인 일반 상품 id. order 가 답하지 않으면 빈 집합(품절 표시를 뺀다) */
    private Set<UUID> soldOut(Collection<UUID> inStockProductIds) {
        if (inStockProductIds.isEmpty()) {
            return Set.of();
        }
        Map<UUID, List<UUID>> options = listings.findActiveOptionIds(inStockProductIds);
        Map<UUID, Integer> available;
        try {
            List<UUID> optionIds = new ArrayList<>();
            options.values().forEach(optionIds::addAll);
            available = stocks.available(optionIds);
        } catch (BusinessException e) {
            log.warn("목록의 품절을 order 재고로 가르지 못했다 — 품절 표시를 빼고 보낸다 products={}", inStockProductIds.size(), e);
            return Set.of();
        }
        Set<UUID> soldOut = new HashSet<>();
        for (UUID productId : inStockProductIds) {
            boolean anyLeft = options.getOrDefault(productId, List.of()).stream().anyMatch(option -> available.getOrDefault(option, 0) > 0);
            if (!anyLeft) {
                soldOut.add(productId);
            }
        }
        return soldOut;
    }
}
