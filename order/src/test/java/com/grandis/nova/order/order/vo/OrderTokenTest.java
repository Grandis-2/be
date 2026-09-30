package com.grandis.nova.order.order.vo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTokenTest {

    @Test
    void issuedTokenIsCanonicalUuid() {
        assertThat(OrderToken.issue().value()).hasSize(36).isLowerCase();
    }

    // DB 칸이 대소문자를 구별하므로 대문자 토큰은 같은 주문을 찾지 못한다.
    @Test
    void rejectsNonCanonicalForms() {
        assertThatThrownBy(() -> new OrderToken("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderToken("3F2504E0-4F89-11D3-9A0C-0305E82C3301"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
