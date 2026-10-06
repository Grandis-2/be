package com.grandis.nova.payment.client.toss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결과 불명을 푸는 방법. 토스가 응답을 했으면(코드 · 상태 · 짝 · 본문) 같은 멱등 키로 다시 보내도 같은 응답이 오므로 조회,
 * 응답을 받지 못했을 때만 재전송이다. 이 표를 코드와 따로 적는다 — 사유가 늘면 여기에도 적어야 통과한다.
 */
class UnknownReasonTest {

    static final Map<UnknownReason, UnknownReason.Resolution> EXPECTED = new EnumMap<>(Map.of(
            UnknownReason.TIMEOUT, UnknownReason.Resolution.RESEND,
            UnknownReason.CONNECTION_FAILURE, UnknownReason.Resolution.RESEND,
            UnknownReason.UNREADABLE_RESPONSE, UnknownReason.Resolution.LOOKUP,
            UnknownReason.LISTED_CODE, UnknownReason.Resolution.LOOKUP,
            UnknownReason.UNLISTED_CODE, UnknownReason.Resolution.LOOKUP,
            UnknownReason.UNEXPECTED_STATUS, UnknownReason.Resolution.LOOKUP,
            UnknownReason.MISMATCHED_RESPONSE, UnknownReason.Resolution.LOOKUP));

    @ParameterizedTest
    @EnumSource(UnknownReason.class)
    void everyReasonHasTheAgreedResolution(UnknownReason reason) {
        assertThat(EXPECTED).containsKey(reason);
        assertThat(reason.resolution()).isEqualTo(EXPECTED.get(reason));
    }

    @Test
    void unknownResultExposesItsResolution() {
        assertThat(new TossCommandResult.Unknown(UnknownReason.TIMEOUT, null).resolution())
                .isEqualTo(UnknownReason.Resolution.RESEND);
        assertThat(new TossCommandResult.Unknown(UnknownReason.UNEXPECTED_STATUS, null).resolution())
                .isEqualTo(UnknownReason.Resolution.LOOKUP);
    }
}
