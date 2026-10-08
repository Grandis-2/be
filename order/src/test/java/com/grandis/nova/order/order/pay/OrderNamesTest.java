package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderNamesTest {

    @Test
    void joinsProductAndOptionTitles() {
        assertThat(OrderNames.of(List.of(item("Nova 1", "블랙 / 256GB")))).isEqualTo("Nova 1 블랙 / 256GB");
    }

    @Test
    void keepsNameAtLimit() {
        String name = OrderNames.of(List.of(item("가".repeat(50), "나".repeat(49))));

        assertThat(name.length()).isEqualTo(OrderNames.MAX_LENGTH);
        assertThat(name).doesNotEndWith(OrderNames.ELLIPSIS);
    }

    // 상품명(≤100) · 옵션명(≤120)을 합치면 토스 상한(100)을 넘을 수 있다 — 상한에 맞춰 자르고 잘렸다고 표시한다.
    @Test
    void truncatesLongNameToLimit() {
        String name = OrderNames.of(List.of(item("가".repeat(100), "나".repeat(120))));

        assertThat(name.length()).isEqualTo(OrderNames.MAX_LENGTH);
        assertThat(name).isEqualTo("가".repeat(99) + OrderNames.ELLIPSIS);
    }

    // 길이는 UTF-16 단위로 센다(이모지 하나 = 2). 자르는 자리가 서로게이트 쌍 가운데면 한 글자 앞에서 자른다.
    @Test
    void countsUtf16UnitsAndKeepsSurrogatePairs() {
        String name = OrderNames.of(List.of(item("😀".repeat(100), "옵션")));

        assertThat(name).isEqualTo("😀".repeat(49) + OrderNames.ELLIPSIS);
        assertThat(name.length()).isLessThanOrEqualTo(OrderNames.MAX_LENGTH);
    }

    // 지금은 사전예약이라 늘 한 줄이다. 여러 줄이면 첫 줄 이름 뒤에 나머지 수를 붙이고, 자를 때도 그 꼬리는 남긴다.
    @Test
    void namesFirstLineAndCountsTheRest() {
        assertThat(OrderNames.of(List.of(item("Nova 1", "블랙"), item("Nova 2", "화이트"), item("Nova 3", "실버"))))
                .isEqualTo("Nova 1 블랙 외 2건");

        String truncated = OrderNames.of(List.of(item("가".repeat(100), "나"), item("Nova 2", "화이트")));
        assertThat(truncated.length()).isEqualTo(OrderNames.MAX_LENGTH);
        assertThat(truncated).endsWith(OrderNames.ELLIPSIS + " 외 1건");
    }

    @Test
    void rejectsOrderWithoutItems() {
        assertThatThrownBy(() -> OrderNames.of(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    private static OrderItem item(String productTitle, String optionTitle) {
        return new OrderItem(TestIds.id(1), TestIds.id(1), new OrderLine(TestIds.id(1), TestIds.id(1), Quantity.ONE, Money.won(1000), productTitle, optionTitle));
    }
}
