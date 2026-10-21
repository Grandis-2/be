package com.grandis.nova.preorder.deadletter.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 목록 · 일괄 후보의 되돌리기 판정은 지금 받는 종류만 — 되돌리기가 원문을 다시 읽어 내리는 판정과 같아야 한다. */
class FailureReasonTest {

    @Test
    void 처리_실패여도_지금_받지_않는_종류면_되돌릴_수_없다() {
        assertThat(FailureReason.PROCESSING_FAILED.redrivableFor("PREORDER_EXPIRY_REQUESTED")).isFalse();
        assertThat(FailureReason.PROCESSING_FAILED.redrivableFor("PREORDER_PAYMENT_STARTED")).isTrue();
    }

    @Test
    void 모르던_종류는_지금_받게_됐으면_되돌릴_수_있다() {
        assertThat(FailureReason.UNKNOWN_EVENT_TYPE.redrivableFor("PREORDER_PAYMENT_STARTED")).isTrue();
        assertThat(FailureReason.UNKNOWN_EVENT_TYPE.redrivableFor("SOMETHING_NEW")).isFalse();
    }

    @Test
    void 읽지_못한_원문은_되돌릴_수_없다() {
        assertThat(FailureReason.UNREADABLE_BODY.redrivableFor(null)).isFalse();
    }
}
