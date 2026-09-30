package com.grandis.nova.payment.vo;

import com.grandis.nova.payment.domain.enums.TargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentValuesTest {

    @Test
    void issuedProviderOrderIdsAreUniqueUuidsWithinTossRule() {
        ProviderOrderId first = ProviderOrderId.issue();
        ProviderOrderId second = ProviderOrderId.issue();

        assertThat(first).isNotEqualTo(second);
        assertThat(first.value()).hasSize(36).matches("[0-9a-f-]+");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc12", "주문번호123456", "order id 1", "order.id.1"})
    void providerOrderIdRejectsWhatTossRejects(String value) {
        assertThatThrownBy(() -> new ProviderOrderId(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerOrderIdAcceptsSixToSixtyFourOfLettersDigitsDashUnderscore() {
        assertThat(new ProviderOrderId("aB3_-z").value()).isEqualTo("aB3_-z");
        assertThat(new ProviderOrderId("a".repeat(64)).value()).hasSize(64);
        assertThatThrownBy(() -> new ProviderOrderId("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuedIdempotencyKeysAreUniqueAndFitTheColumn() {
        IdempotencyKey first = IdempotencyKey.issue();

        assertThat(first).isNotEqualTo(IdempotencyKey.issue());
        assertThat(first.value().length()).isLessThanOrEqualTo(IdempotencyKey.MAX_LENGTH);
        assertThatThrownBy(() -> new IdempotencyKey(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyKey("a".repeat(IdempotencyKey.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 결제 키는 로그에 남기지 않는다(R9). 레코드 기본 toString 은 값을 그대로 싣는다.
    @Test
    void providerPaymentKeyNeverPrintsItsValue() {
        ProviderPaymentKey paymentKey = new ProviderPaymentKey("tgen_20260929abcdef");

        assertThat(paymentKey.toString()).doesNotContain("tgen_20260929abcdef");
        assertThatThrownBy(() -> new ProviderPaymentKey("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderPaymentKey("k".repeat(ProviderPaymentKey.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paymentTargetNeedsTypeAndPositiveId() {
        assertThat(PaymentTarget.order(1L).type()).isEqualTo(TargetType.ORDER);
        assertThat(PaymentTarget.drawEntry(1L).type()).isEqualTo(TargetType.DRAW_ENTRY);
        assertThatThrownBy(() -> PaymentTarget.order(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentTarget.order(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void moneyIsWholeNonNegativeWonUpToTwelveDigits() {
        assertThat(new Money(new BigDecimal("1000.0"))).isEqualTo(Money.won(1000));
        assertThatThrownBy(() -> Money.won(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(new BigDecimal("0.5"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.won(1_000_000_000_000L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaseTokensAreUnique() {
        assertThat(LeaseToken.issue()).isNotEqualTo(LeaseToken.issue());
        assertThatThrownBy(() -> new LeaseToken("")).isInstanceOf(IllegalArgumentException.class);
    }

    // 결제사가 준 긴 메시지 때문에 반영 자체가 실패하면 결과를 잃는다. 칸에 맞게 자르고, 글자 중간에서 자르지 않는다.
    @Test
    void providerErrorTruncatesToColumnsWithoutSplittingCharacters() {
        String longMessage = "😀".repeat(ProviderError.MESSAGE_MAX + 5);

        ProviderError error = new ProviderError("C".repeat(ProviderError.CODE_MAX + 3), longMessage);

        assertThat(error.code()).hasSize(ProviderError.CODE_MAX);
        assertThat(error.message().codePointCount(0, error.message().length())).isEqualTo(ProviderError.MESSAGE_MAX);
        assertThat(error.message()).isEqualTo("😀".repeat(ProviderError.MESSAGE_MAX));
    }

    @Test
    void providerErrorNeedsCodeButNotMessage() {
        assertThat(new ProviderError("TIMEOUT", null).message()).isNull();
        assertThatThrownBy(() -> new ProviderError(" ", "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
