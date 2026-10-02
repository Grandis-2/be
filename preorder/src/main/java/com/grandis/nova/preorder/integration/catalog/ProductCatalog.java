package com.grandis.nova.preorder.integration.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * catalog 내부 API 가 돌려주는 상품과 그 옵션 전체. 상태 문자열은 catalog 소유라 enum 으로 옮기지 않는다.
 */
public record ProductCatalog(
        Long productId,
        String title,
        String saleMode,
        String status,
        List<Option> options
) {

    public ProductCatalog {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 사전예약으로 파는 판매 중 상품인가. 옵션 판매 여부는 {@link OptionSnapshot#isOnSale} 이 따로 본다. */
    public boolean isOnPreorderSale() {
        return OptionSnapshot.PREORDER.equals(saleMode) && OptionSnapshot.ACTIVE.equals(status);
    }

    /** 이 상품의 옵션이 아니면 비어 있다(다른 상품의 옵션 id 를 섞어 보내는 요청). */
    public Optional<OptionSnapshot> snapshot(Long optionId) {
        return options.stream()
                .filter(option -> option.optionId().equals(optionId))
                .findFirst()
                .map(option -> new OptionSnapshot(productId, title, saleMode, status,
                        option.optionId(), option.sku(), option.title(), option.price(), option.status()));
    }

    public record Option(
            Long optionId,
            String sku,
            String title,
            BigDecimal price,
            String status
    ) {
    }
}
