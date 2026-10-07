package com.grandis.nova.preorder.integration.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * catalog 내부 API 가 돌려주는 상품과 그 옵션 전체. 상태 문자열은 catalog 소유라 enum 으로 옮기지 않는다.
 * 공개 전 · 등록 미완료 상품도 그대로 온다 — 숨길지는 목적별로 여기서 가른다.
 *
 * @param visible               회원에게 공개했는가. 칸이 없으면(이 칸을 모르는 catalog) null — 숨기지 않는다
 * @param registrationCompleted 등록의 모든 단계가 끝났는가. 칸이 없으면 null — 숨기지 않는다
 */
public record ProductCatalog(
        UUID productId,
        String title,
        String saleMode,
        String status,
        Boolean visible,
        Boolean registrationCompleted,
        List<Option> options
) {

    public ProductCatalog {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 사전예약 상품인가. 회차 설정은 이것만 본다 — 공개 · 판매 전에도 관리자가 일정을 잡을 수 있어야 한다. */
    public boolean isPreorderProduct() {
        return OptionSnapshot.PREORDER.equals(saleMode);
    }

    /**
     * 지금 접수를 받는 사전예약 상품인가 — 판매 중이고 공개됐고 등록이 끝났다. 옵션 판매 여부는 {@link OptionSnapshot#isOnSale} 이 본다.
     * 공개 전 상품은 존재를 알리지 않도록 호출하는 쪽이 "상품 없음" 으로 답한다.
     */
    public boolean isOnPreorderSale() {
        return isPreorderProduct() && OptionSnapshot.ACTIVE.equals(status)
                && isVisible() && !Boolean.FALSE.equals(registrationCompleted);
    }

    /** 회원에게 공개했는가. 칸이 없으면(이 칸을 모르는 catalog) 숨기지 않는다. */
    public boolean isVisible() {
        return !Boolean.FALSE.equals(visible);
    }

    /** 이 상품의 옵션이 아니면 비어 있다(다른 상품의 옵션 id 를 섞어 보내는 요청). */
    public Optional<OptionSnapshot> snapshot(UUID optionId) {
        return options.stream()
                .filter(option -> option.optionId().equals(optionId))
                .findFirst()
                .map(option -> new OptionSnapshot(productId, title, saleMode, status,
                        option.optionId(), option.sku(), option.title(), option.price(), option.status()));
    }

    public record Option(
            UUID optionId,
            String sku,
            String title,
            BigDecimal price,
            String status
    ) {
    }
}
