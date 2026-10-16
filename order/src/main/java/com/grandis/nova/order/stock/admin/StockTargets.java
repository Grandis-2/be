package com.grandis.nova.order.stock.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.web.ApiError;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.stock.domain.enums.SaleMode;
import com.grandis.nova.order.stock.domain.repository.CatalogOptions;
import com.grandis.nova.order.web.ValidationFailures;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 관리자 재고 요청의 대상 확인 — 재고를 세는 상품인가, 옵션이 그 상품의 것인가. catalog 표를 잠그지 않고 읽는다({@link CatalogOptions} 의 전제).
 * 재고를 바꾸는 트랜잭션 안에서 부른다.
 */
final class StockTargets {

    private final CatalogOptions catalog;

    StockTargets(CatalogOptions catalog) {
        this.catalog = catalog;
    }

    /** @throws BusinessException PRODUCT_NOT_FOUND · STOCK_NOT_TRACKED(사전예약 상품) */
    void requireStockedProduct(UUID productId) {
        SaleMode saleMode = catalog.findSaleMode(productId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.PRODUCT_NOT_FOUND));
        if (saleMode != SaleMode.IN_STOCK) {
            throw new BusinessException(OrderErrorCode.STOCK_NOT_TRACKED);
        }
    }

    /** 없는 옵션 · 다른 상품의 옵션은 계약 오류라 400 이다. 요청 순서의 위치(items[i])로 모두 알린다. */
    void requireOwnedOptions(UUID productId, List<UUID> optionIds) {
        Set<UUID> owned = catalog.findOwnedOptionIds(productId, optionIds);
        List<ApiError.Violation> violations = new ArrayList<>();
        for (int i = 0; i < optionIds.size(); i++) {
            if (!owned.contains(optionIds.get(i))) {
                violations.add(new ApiError.Violation("items[%d].optionId".formatted(i), "이 상품의 옵션이 아닙니다."));
            }
        }
        if (!violations.isEmpty()) {
            throw ValidationFailures.of(violations);
        }
    }

    /** 경로의 옵션 하나. 아니면 400(optionId). */
    void requireOwnedOption(UUID productId, UUID optionId) {
        if (!catalog.findOwnedOptionIds(productId, List.of(optionId)).contains(optionId)) {
            throw ValidationFailures.of("optionId", "이 상품의 옵션이 아닙니다.");
        }
    }
}
