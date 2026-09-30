package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;

import java.util.Objects;

/**
 * 주문할 상품 한 줄. 가격 · 이름은 예약 접수 시점의 스냅샷이다 — 카탈로그를 다시 읽지 않는다.
 * 저장되면 {@link OrderItem} 이 이것을 그대로 들고 있다.
 *
 * 이름 길이는 DB 칸(product_title_snapshot 100 · option_title_snapshot 120)과 같다. MySQL 은 글자 수로 세므로
 * 코드 포인트로 센다.
 */
public record OrderLine(
        Long productId,
        Long optionId,
        Quantity quantity,
        Money unitPrice,
        String productTitle,
        String optionTitle
) {

    public OrderLine {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        requireLength(productTitle, "productTitle", 100);
        requireLength(optionTitle, "optionTitle", 120);
    }

    public Money subtotal() {
        return unitPrice.times(quantity);
    }

    private static void requireLength(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException("%s 는 %d자 이하여야 한다".formatted(field, maxLength));
        }
    }
}
