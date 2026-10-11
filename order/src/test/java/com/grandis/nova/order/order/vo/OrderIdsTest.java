package com.grandis.nova.order.order.vo;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderIdsTest {

    // 응답이 내보내는 모양(소문자 표준형 UUID)만 받는다. 그 밖은 없는 주문과 같다
    @Test
    void parseTreatsMalformedAsAbsent() {
        assertThat(OrderIds.parse("3f2504e0-4f89-11d3-9a0c-0305e82c3301"))
                .contains(UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301"));
        assertThat(OrderIds.parse("not-a-uuid")).isEmpty();
        assertThat(OrderIds.parse("3F2504E0-4F89-11D3-9A0C-0305E82C3301")).isEmpty();
        assertThat(OrderIds.parse("1-1-1-1-1")).isEmpty();
        assertThat(OrderIds.parse(null)).isEmpty();
    }
}
