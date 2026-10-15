package com.grandis.nova.order.outbox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** member 가 DLQ 로 보낼 메시지(수신자 0명 · 1,000명 초과 · 겹침 · 길이 초과)는 만들지 못한다. */
class NotificationRequestedTest {

    static final UUID ENTRY_ID = UUID.fromString("0199a3f2-8a10-7b21-9c32-4d5e6f708192");
    static final NotificationRequested.Target TARGET =
            new NotificationRequested.Target(NotificationRequested.TargetType.DRAW, "0199a3f1-0000-7000-8000-000000000001");

    @Test
    void 수신자는_겹치지_않게_1명부터_1000명까지다() {
        assertThat(of(customers(1000), "제목", "본문").recipientIds()).hasSize(1000);

        UUID customer = UUID.randomUUID();
        assertThatThrownBy(() -> of(List.of(), "제목", "본문")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(customers(1001), "제목", "본문")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(List.of(customer, customer), "제목", "본문")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 제목은_100자_본문은_500자까지_코드_포인트로_센다() {
        assertThat(of(customers(1), "🎉".repeat(100), "본문").title()).hasSize(200);

        assertThatThrownBy(() -> of(customers(1), "가".repeat(101), "본문")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(customers(1), "제목", "가".repeat(501))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of(customers(1), " ", "본문")).isInstanceOf(IllegalArgumentException.class);
    }

    private static NotificationRequested of(List<UUID> recipients, String title, String body) {
        return new NotificationRequested(ENTRY_ID, recipients, NotificationRequested.Kind.DRAW_WON, title, body, TARGET);
    }

    private static List<UUID> customers(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }
}
