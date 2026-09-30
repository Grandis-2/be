package com.grandis.nova.payment.client.toss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 토스가 거절할 요청(문서의 길이 · 형식 규칙)은 보내기 전에 막는다 — 보내면 INVALID_REQUEST 로 실패 확정이 된다. */
class TossRequestsTest {

    // 멱등 키: 최대 300자(넘으면 400 INVALID_IDEMPOTENCY_KEY).
    @Test
    void idempotencyKeyLengthIsBoundedBy300() {
        assertThatCode(() -> new TossIdempotencyKey("k".repeat(300))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new TossIdempotencyKey("k".repeat(301))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void idempotencyKeyIsRequired(String value) {
        assertThatThrownBy(() -> new TossIdempotencyKey(value)).isInstanceOf(IllegalArgumentException.class);
    }

    // orderId: 영문 대소문자 · 숫자 · -, _ 로 6~64자.
    @ParameterizedTest
    @ValueSource(strings = {"abc12", "has space-000", "한글주문번호000", "slash/000000"})
    void orderIdMustFollowTossFormat(String orderId) {
        assertThatThrownBy(() -> new TossConfirmRequest(TossStubs.PAYMENT_REF, orderId, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderIdBoundaries() {
        assertThatCode(() -> new TossConfirmRequest(TossStubs.PAYMENT_REF, "a_b-c1", 1000)).doesNotThrowAnyException();
        assertThatCode(() -> new TossConfirmRequest(TossStubs.PAYMENT_REF, "o".repeat(64), 1000))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new TossConfirmRequest(TossStubs.PAYMENT_REF, "o".repeat(65), 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void amountMustBePositive(long amount) {
        assertThatThrownBy(() -> new TossConfirmRequest(TossStubs.PAYMENT_REF, TossStubs.ORDER_REF, amount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // paymentKey: 최대 200자. 경로에 들어가므로 비면 다른 API(/v1/payments/)를 부르게 된다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void paymentKeyIsRequired(String paymentKey) {
        assertThatThrownBy(() -> new TossConfirmRequest(paymentKey, TossStubs.ORDER_REF, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TossCancelRequest(paymentKey, "사유"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 문자 집합은 문서에 없어 허용 목록을 두지 않는다. 실제 키일 수 없는 것만 막는다 — 경로 세그먼트, 제어 문자(로그 주입).
    @ParameterizedTest
    @ValueSource(strings = {".", "..", "tviva2026\nFAKE log line", "tviva2026\rabc", "tviva\u0000key"})
    void paymentKeyCannotBePathSegmentOrContainControlCharacters(String paymentKey) {
        assertThatThrownBy(() -> new TossConfirmRequest(paymentKey, TossStubs.ORDER_REF, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TossCancelRequest(paymentKey, "사유")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paymentKeyLengthIsBoundedBy200() {
        assertThatThrownBy(() -> new TossConfirmRequest("p".repeat(201), TossStubs.ORDER_REF, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // cancelReason: 필수, 최대 200자.
    @Test
    void cancelReasonIsRequiredAndBoundedBy200() {
        assertThatThrownBy(() -> new TossCancelRequest(TossStubs.PAYMENT_REF, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TossCancelRequest(TossStubs.PAYMENT_REF, "사".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TossCancelRequest(TossStubs.PAYMENT_REF, "사".repeat(200)).cancelReason()).hasSize(200);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void merchantCredentialIsRequired(String value) {
        assertThatThrownBy(() -> new TossProperties(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
