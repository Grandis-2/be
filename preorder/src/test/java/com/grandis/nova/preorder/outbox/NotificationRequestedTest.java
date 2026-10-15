package com.grandis.nova.preorder.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** member 가 DLQ 로 보낼 메시지(수신자 0명 · 1,000명 초과 · 겹침)는 만들지 못한다. 문구는 계약 표와 같다. */
class NotificationRequestedTest {

    @Test
    void 수신자는_1명부터_1000명까지다() {
        assertThat(to(customers(1000)).recipientIds()).hasSize(1000);

        assertThatThrownBy(() -> to(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> to(customers(1001))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 겹친_수신자는_거절한다() {
        UUID customer = UUID.randomUUID();

        assertThatThrownBy(() -> to(List.of(customer, customer))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 등록_완료는_예약한_회원에게_내_예약_상세로() {
        UUID preorderId = UUID.randomUUID();
        UUID customer = UUID.randomUUID();

        NotificationRequested registered = NotificationRequested.preorderRegistered(preorderId, "token", customer);

        assertThat(registered.kind()).isEqualTo(NotificationRequested.Kind.PREORDER_REGISTERED);
        assertThat(registered.title()).isEqualTo("사전예약 등록 완료");
        assertThat(registered.body()).isEqualTo("사전예약 등록이 완료됐어요. 24시간 안에 결제해 주세요.");
        assertThat(registered.recipientIds()).containsExactly(customer);
        assertThat(registered.aggregateType()).isEqualTo(AggregateType.PREORDER);
        assertThat(registered.aggregateId()).isEqualTo(preorderId);
        assertThat(registered.target()).isEqualTo(new NotificationRequested.Target(NotificationRequested.TargetType.PREORDER,
                "token"));
    }

    @ParameterizedTest(name = "{0} · 결제 {1}")
    @CsvSource({
            "USER, false, 사전예약 취소가 완료됐어요.",
            "USER, true, 사전예약 취소가 완료됐어요. 결제하신 금액은 환불됐어요.",
            "EXPIRY, false, 결제 기한이 지나 사전예약이 취소됐어요.",
            "EXPIRY, true, 결제 기한이 지나 사전예약이 취소됐어요. 결제하신 금액은 환불됐어요.",
            "OTHER, false, 사전예약이 취소됐어요.",
            "OTHER, true, 사전예약이 취소됐어요. 결제하신 금액은 환불됐어요."
    })
    void 취소_문구는_까닭과_결제_여부로_가른다(NotificationRequested.CancelCause cause, boolean paid, String body) {
        UUID preorderId = UUID.randomUUID();
        UUID customer = UUID.randomUUID();

        NotificationRequested canceled = NotificationRequested.preorderCanceled(preorderId, "token", customer, cause, paid);

        assertThat(canceled.body()).isEqualTo(body);
        assertThat(canceled.title()).isEqualTo("사전예약 취소");
        assertThat(canceled.kind()).isEqualTo(NotificationRequested.Kind.PREORDER_CANCELED);
        assertThat(canceled.recipientIds()).containsExactly(customer);
        assertThat(canceled.aggregateType()).isEqualTo(AggregateType.PREORDER);
        assertThat(canceled.aggregateId()).isEqualTo(preorderId);
        assertThat(canceled.target()).isEqualTo(new NotificationRequested.Target(NotificationRequested.TargetType.PREORDER,
                "token"));
    }

    private static NotificationRequested to(List<UUID> recipients) {
        return new NotificationRequested(AggregateType.PREORDER, UUID.randomUUID(), recipients,
                NotificationRequested.Kind.PREORDER_REGISTERED, "제목", "본문",
                new NotificationRequested.Target(NotificationRequested.TargetType.PREORDER, "token"));
    }

    private static List<UUID> customers(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }
}
