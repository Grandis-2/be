package com.grandis.nova.order.order.domain.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 주문 번호 앞부분 → id 범위. 글자(4비트) 단위로 채우고, 윗비트가 1 인 값도 부호 없이 읽는다. */
class OrderIdPrefixTest {

    @Test
    void eightDigitsCoverEveryIdWithThatPrefix() {
        OrderIdPrefix prefix = OrderIdPrefix.parse("0193ABcd").orElseThrow();

        assertThat(prefix.lowest()).isEqualTo(UUID.fromString("0193abcd-0000-0000-0000-000000000000"));
        assertThat(prefix.highest()).isEqualTo(UUID.fromString("0193abcd-ffff-ffff-ffff-ffffffffffff"));
        assertThat(prefix.exact()).isFalse();
    }

    @Test
    void oddLengthPadsByDigitNotByByte() {
        OrderIdPrefix prefix = OrderIdPrefix.parse("0193abcd-e").orElseThrow();

        assertThat(prefix.lowest()).isEqualTo(UUID.fromString("0193abcd-e000-0000-0000-000000000000"));
        assertThat(prefix.highest()).isEqualTo(UUID.fromString("0193abcd-efff-ffff-ffff-ffffffffffff"));
    }

    @Test
    void highBitIsReadUnsigned() {
        OrderIdPrefix prefix = OrderIdPrefix.parse("ffffffff89abcdef").orElseThrow();

        assertThat(prefix.lowest()).isEqualTo(UUID.fromString("ffffffff-89ab-cdef-0000-000000000000"));
        assertThat(prefix.highest()).isEqualTo(UUID.fromString("ffffffff-89ab-cdef-ffff-ffffffffffff"));
    }

    @Test
    void wholeIdWithOrWithoutHyphensIsExact() {
        UUID id = UUID.fromString("0193abcd-ef01-7234-8567-89abcdef0123");

        assertThat(OrderIdPrefix.parse(id.toString())).hasValue(new OrderIdPrefix(id, id));
        assertThat(OrderIdPrefix.parse(id.toString().replace("-", "").toUpperCase())).hasValueSatisfying(p -> assertThat(p.exact()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0193abc", "0193abcg", "0193abcd-ef01-7234-8567-89abcdef01234", "0193 abcd"})
    void rejectsShortLongOrNonHex(String value) {
        assertThat(OrderIdPrefix.parse(value)).isEmpty();
    }
}
