package com.grandis.nova.payment.confirm;

import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossConfirmCodes;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 첫 승인 요청의 결과 → (결과, 코드) 시계열. 첫 전송이 낼 수 있는 결과는 모두 미리 등록한 시계열에 떨어져야 한다. */
class ConfirmOutcomeMetricsTest {

    static final Instant APPROVED_AT = Instant.parse("2026-10-02T01:02:03Z");
    static final PaymentTransaction HELD = PaymentTransaction.openCapture(
            PaymentTarget.order(UUID.fromString("0199a3c4-0000-7000-8000-000000000007")), Money.won(15000),
            Instant.parse("2026-10-02T01:00:00Z"));

    final MeterRegistry registry = new SimpleMeterRegistry();
    final ConfirmOutcomeMetrics metrics = new ConfirmOutcomeMetrics(registry);

    // 저장소 밖의 Grafana 경보 규칙이 이 이름 · 태그(payment_confirm_outcomes_total)에 기댄다 — 바꾸면 경보가 조용히 끊긴다
    @Test
    void nameAndTagsAreFixed() {
        assertThat(registry.get("payment.confirm.outcomes").tag("outcome", "CONFIRMED").tag("code", "NONE").counter())
                .isNotNull();
    }

    @Test
    void preregistersPairsAtZero() {
        assertThat(registry.find(ConfirmOutcomeMetrics.METER).counters())
                .isNotEmpty()
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(count("CONFIRMED", "NONE")).isZero();
        assertThat(count("REJECTED", "REJECT_CARD_PAYMENT")).isZero();
        assertThat(count("IN_PROGRESS", "PROVIDER_ERROR")).isZero();
        assertThat(count("UNKNOWN", "TIMEOUT")).isZero();
    }

    @Test
    void countsEachResultWithItsCode() {
        metrics.record(TossOutcomes.of(succeeded(15000, APPROVED_AT), HELD));
        metrics.record(TossOutcomes.of(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "한도초과"), HELD));
        metrics.record(TossOutcomes.of(new TossCommandResult.Transient("PROVIDER_ERROR", "일시 오류"), HELD));
        metrics.record(TossOutcomes.of(new TossCommandResult.Processing("IDEMPOTENT_REQUEST_PROCESSING"), HELD));
        metrics.record(TossOutcomes.of(new TossCommandResult.Unknown(UnknownReason.TIMEOUT, null), HELD));
        metrics.record(TossOutcomes.of(new TossCommandResult.Unknown(UnknownReason.LISTED_CODE, "CARD_PROCESSING_ERROR"), HELD));
        metrics.record(TossOutcomes.of(succeeded(14000, APPROVED_AT), HELD));

        assertThat(count("CONFIRMED", "NONE")).isEqualTo(1);
        assertThat(count("REJECTED", "REJECT_CARD_PAYMENT")).isEqualTo(1);
        assertThat(count("IN_PROGRESS", "PROVIDER_ERROR")).isEqualTo(1);
        assertThat(count("IN_PROGRESS", "IDEMPOTENT_REQUEST_PROCESSING")).isEqualTo(1);
        assertThat(count("UNKNOWN", "TIMEOUT")).isEqualTo(1);
        assertThat(count("UNKNOWN", "CARD_PROCESSING_ERROR")).isEqualTo(1);
        assertThat(count("UNKNOWN", TossOutcomes.AMOUNT_MISMATCH)).isEqualTo(1);
    }

    // 표에 없는 토스 코드는 값이 정해져 있지 않다 — 그대로 태그에 실으면 시계열이 끝없이 늘고, 미리 등록할 수도 없다
    @Test
    void unlistedCodesShareOneSeries() {
        int before = registry.getMeters().size();

        metrics.record(TossOutcomes.of(new TossCommandResult.Unknown(UnknownReason.UNLISTED_CODE, "NEW_CODE_A"), HELD));
        metrics.record(TossOutcomes.of(new TossCommandResult.Unknown(UnknownReason.UNLISTED_CODE, "NEW_CODE_B"), HELD));

        assertThat(count("UNKNOWN", ConfirmOutcomeMetrics.UNLISTED)).isEqualTo(2);
        assertThat(registry.getMeters()).hasSize(before);
    }

    // 클라이언트에 새 갈래가 생겨 미리 등록하지 않은 코드가 와도 시계열은 늘지 않는다 — 결과 종류는 그대로 둔다
    @Test
    void codesOutsideThePreregisteredSetAreUnlisted() {
        int before = registry.getMeters().size();

        metrics.record(TossOutcomes.of(new TossCommandResult.Rejected("NEW_REJECTION", "문구"), HELD));
        metrics.record(TossOutcomes.of(new TossCommandResult.Transient("NEW_TRANSIENT", "문구"), HELD));
        metrics.record(TossOutcomes.of(new TossCommandResult.Unknown(UnknownReason.TIMEOUT, "NEW_CODE"), HELD));

        assertThat(count("REJECTED", ConfirmOutcomeMetrics.UNLISTED)).isEqualTo(1);
        assertThat(count("IN_PROGRESS", ConfirmOutcomeMetrics.UNLISTED)).isEqualTo(1);
        assertThat(count("UNKNOWN", ConfirmOutcomeMetrics.UNLISTED)).isEqualTo(1);
        assertThat(registry.getMeters()).hasSize(before);
    }

    // 결과를 만드는 갈래마다 한 건씩: 분류표의 코드 · 코드 없는 불명(사유마다) · 표에 없는 코드 · 성공 응답의 대조 실패. 표의 코드가
    // 클라이언트가 실제로 낸 종류의 목록에 있는지는 TossErrorTableTest 가 본다. 여기서는 미리 등록한 시계열이 저마다 정확히 한 번 올라야
    // 한다 — 종류를 잘못 옮기거나 코드를 잘못 꺼내면 어느 시계열은 0, 어느 시계열은 2 가 된다. 실패 종류의 UNLISTED_CODE 중 첫 전송이
    // 낼 수 있는 것은 불명뿐이라 거절 · 처리 중 쪽은 0 이다
    @Test
    void everyFirstSendResultKeepsItsOwnCode() {
        assertThat(List.of(TossCommandResult.Rejected.class, TossCommandResult.Transient.class,
                TossCommandResult.Processing.class, TossCommandResult.Unknown.class))
                .allSatisfy(kind -> assertThat(TossConfirmCodes.listedCodes(kind)).isNotEmpty());
        List<TossCommandResult> results = new ArrayList<>();
        results.add(succeeded(15000, APPROVED_AT));
        results.add(succeeded(14000, APPROVED_AT));
        results.add(succeeded(15000, null));
        TossConfirmCodes.listedCodes(TossCommandResult.Rejected.class)
                .forEach(code -> results.add(new TossCommandResult.Rejected(code, "문구")));
        TossConfirmCodes.listedCodes(TossCommandResult.Transient.class)
                .forEach(code -> results.add(new TossCommandResult.Transient(code, "문구")));
        TossConfirmCodes.listedCodes(TossCommandResult.Processing.class)
                .forEach(code -> results.add(new TossCommandResult.Processing(code)));
        TossConfirmCodes.listedCodes(TossCommandResult.Unknown.class)
                .forEach(code -> results.add(new TossCommandResult.Unknown(UnknownReason.LISTED_CODE, code)));
        for (UnknownReason reason : UnknownReason.values()) {
            // 표의 코드 · 표에 없는 코드로 끝난 불명은 늘 토스 코드를 싣는다(위 · 아래 입력)
            if (reason != UnknownReason.LISTED_CODE && reason != UnknownReason.UNLISTED_CODE) {
                results.add(new TossCommandResult.Unknown(reason, null));
            }
        }
        results.add(new TossCommandResult.Unknown(UnknownReason.UNLISTED_CODE, "NEW_CODE"));
        int before = registry.getMeters().size();

        results.forEach(result -> metrics.record(TossOutcomes.of(result, HELD)));

        assertThat(registry.getMeters()).hasSize(before);
        assertThat(registry.find(ConfirmOutcomeMetrics.METER).counters()).allSatisfy(counter -> {
            boolean unreachable = ConfirmOutcomeMetrics.UNLISTED.equals(counter.getId().getTag("code"))
                    && !"UNKNOWN".equals(counter.getId().getTag("outcome"));
            assertThat(counter.count()).as(counter.getId().toString()).isEqualTo(unreachable ? 0 : 1);
        });
    }

    private double count(String outcome, String code) {
        return registry.get(ConfirmOutcomeMetrics.METER).tag("outcome", outcome).tag("code", code).counter().count();
    }

    private static TossCommandResult succeeded(long totalAmount, Instant approvedAt) {
        return new TossCommandResult.Succeeded(new TossPayment("tgen_metrics", HELD.providerOrderId().value(),
                TossPaymentStatus.DONE, totalAmount, totalAmount, "카드", approvedAt, "txkey", null));
    }
}
