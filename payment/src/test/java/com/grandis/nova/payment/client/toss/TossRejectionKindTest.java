package com.grandis.nova.payment.client.toss;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 승인 실패 확정 코드의 갈래. 상점 설정 오류를 구매자 사유로 보이면 사용자가 다른 카드로 다시 해도 같은 오류가 난다. */
class TossRejectionKindTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "REJECT_CARD_PAYMENT, BUYER",
            "REJECT_ACCOUNT_PAYMENT, BUYER",
            "EXCEED_MAX_DAILY_PAYMENT_COUNT, BUYER",
            "INVALID_PASSWORD, BUYER",
            "NOT_FOUND_PAYMENT_SESSION, PAYMENT_SESSION_EXPIRED",
            "NOT_FOUND_PAYMENT, PAYMENT_SESSION_EXPIRED",
            "INVALID_REQUEST, OTHER",
            "INVALID_IDEMPOTENCY_KEY, OTHER",
            "UNAUTHORIZED_KEY, OTHER",
            "NOT_REGISTERED_BUSINESS, OTHER",
            // 실패 확정 표 밖의 코드(일시 오류 · 처리 중 · 모르는 코드)는 구매자 사유로 보지 않는다
            "PROVIDER_ERROR, OTHER",
            "ALREADY_PROCESSING_REQUEST, OTHER",
            "SOMETHING_NEW, OTHER"
    })
    void classifiesConfirmRejection(String code, TossRejectionKind expected) {
        assertThat(TossRejectionKind.ofConfirm(code)).isEqualTo(expected);
    }
}
