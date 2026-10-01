package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderLine;

import java.util.List;

/**
 * 결제창에 띄울 주문명. 결제는 이 이름을 저장하지 않는다 — 결제창을 열 때마다 주문 항목에서 다시 만든다.
 *
 * 모양은 "상품명 옵션명"(2026-10-01 사용자 결정), 항목이 여럿이면 뒤에 "외 N건"을 붙인다. 토스 orderName 은 최대 100자라
 * (결제위젯 SDK) 넘으면 이름을 잘라 "…" 로 표시하고 "외 N건" 은 남긴다.
 * 길이는 UTF-16 단위(String.length)로 센다 — 토스(JS)가 이 단위로 세든 코드 포인트로 세든 넘지 않는다. 자르는 자리는
 * 서로게이트 쌍을 가르지 않는다(이모지를 반으로 자르지 않는다).
 */
final class OrderNames {

    static final int MAX_LENGTH = 100;
    static final String ELLIPSIS = "…";

    private OrderNames() {
    }

    static String of(List<OrderItem> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("항목 없는 주문이다");
        }
        OrderLine first = items.getFirst().line();
        String suffix = items.size() == 1 ? "" : " 외 %d건".formatted(items.size() - 1);
        return truncate(first.productTitle() + " " + first.optionTitle(), MAX_LENGTH - suffix.length()) + suffix;
    }

    private static String truncate(String name, int maxLength) {
        if (name.length() <= maxLength) {
            return name;
        }
        int end = maxLength - ELLIPSIS.length();
        if (Character.isLowSurrogate(name.charAt(end))) {
            end--;
        }
        return name.substring(0, end) + ELLIPSIS;
    }
}
