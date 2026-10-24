package com.grandis.nova.payment.recovery;

import com.grandis.nova.payment.domain.enums.TargetType;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.recovery.RecoveryAttention.Reason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 사람 확인 지표의 이름 · 태그와 미리 등록한 쌍. 어느 복구가 어떤 사유로 세는지는 CaptureRecoveryTest · RefundRecoveryTest 가 본다. */
class RecoveryAttentionTest {

    final MeterRegistry registry = new SimpleMeterRegistry();
    final RecoveryAttention attention = new RecoveryAttention(registry);

    // 저장소 밖의 Grafana 경보 규칙이 이 이름 · 태그(payment_recovery_attention_total)에 기댄다 — 바꾸면 경보가 조용히 끊긴다
    @Test
    void nameAndTagsAreFixed() {
        assertThat(registry.get("payment.recovery.attention").tag("type", "CAPTURE").tag("target", "ORDER")
                .tag("reason", "TIMED_OUT").counter()).isNotNull();
    }

    // 승인은 주문 · 응모 × 환불 거절 밖의 세 사유, 환불은 주문 × 네 사유다(응모는 환불하지 않는다)
    @Test
    void preregistersOnlyPairsThatCanOccurAtZero() {
        assertThat(registry.find(RecoveryAttention.METER).counters())
                .hasSize(2 * 3 + 4)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(registry.find(RecoveryAttention.METER).tag("type", "CAPTURE").tag("reason", "REFUND_REJECTED").counters())
                .isEmpty();
        assertThat(registry.find(RecoveryAttention.METER).tag("type", "REFUND").tag("target", "DRAW_ENTRY").counters())
                .isEmpty();
    }

    @Test
    void countsByTypeTargetAndReason() {
        attention.record(TransactionType.CAPTURE, TargetType.DRAW_ENTRY, Reason.TIMED_OUT);
        attention.record(TransactionType.REFUND, TargetType.ORDER, Reason.REFUND_REJECTED);
        attention.record(TransactionType.REFUND, TargetType.ORDER, Reason.REFUND_REJECTED);

        assertThat(count("CAPTURE", "DRAW_ENTRY", "TIMED_OUT")).isEqualTo(1);
        assertThat(count("REFUND", "ORDER", "REFUND_REJECTED")).isEqualTo(2);
        assertThat(count("CAPTURE", "ORDER", "TIMED_OUT")).isZero();
    }

    private double count(String type, String target, String reason) {
        Counter counter = registry.get(RecoveryAttention.METER).tag("type", type).tag("target", target).tag("reason", reason)
                .counter();
        return counter.count();
    }
}
