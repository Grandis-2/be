package com.grandis.nova.order.order.query;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.order.domain.repository.OrderPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCursorTest {

    @Test
    void roundTripsPosition() {
        OrderPosition position = new OrderPosition(Instant.parse("2026-09-25T01:02:03.123456Z"), 42L);

        assertThat(OrderCursor.decode(OrderCursor.encode(position))).isEqualTo(position);
    }

    @Test
    void acceptsBoundsOfStorableRange() {
        assertThat(OrderCursor.decode(OrderCursor.encode(new OrderPosition(OrderCursor.MIN_CREATED_AT, 1L))))
                .isNotNull();
        Instant last = Instant.parse("9999-12-31T23:59:59.999999Z");
        assertThat(OrderCursor.decode(OrderCursor.encode(new OrderPosition(last, 1L))).createdAt()).isEqualTo(last);
    }

    /* 마이크로초 아래 자리는 칼럼에 바인딩될 때 반올림된다. 버리고 나서 범위를 봐야 상한을 넘지 않는다. */
    @Test
    void subMicrosecondDigitsAreDroppedBeforeRangeCheck() {
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("9999-12-31T23:59:59.9999995Z|1".getBytes(StandardCharsets.UTF_8));

        assertThat(OrderCursor.decode(cursor).createdAt()).isEqualTo(Instant.parse("9999-12-31T23:59:59.999999Z"));
    }

    @Test
    void missingCursorMeansFirstPage() {
        assertThat(OrderCursor.decode(null)).isNull();
        assertThat(OrderCursor.decode(" ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"!!not-base64!!", "어제|3", "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z|x",
            "2026-10-01T00:00:00Z|1|2",
            // 형식은 맞지만 저장될 수 없는 값 — 그대로 쿼리에 넣으면 DB 거부 · 변환 넘침으로 500
            "+10000-01-01T00:00:00Z|1", "+1000000000-12-31T23:59:59Z|1", "0999-12-31T23:59:59Z|1",
            "2026-10-01T00:00:00Z|0", "2026-10-01T00:00:00Z|-1"})
    void malformedCursorIsValidationFailure(String raw) {
        String cursor = raw.startsWith("!!") ? raw
                : Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> OrderCursor.decode(cursor))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
