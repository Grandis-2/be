package com.grandis.nova.waitingroom.domain.queue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.grandis.nova.waitingroom.support.TestIds.customerId;
import static com.grandis.nova.waitingroom.support.TestIds.productKey;
import static org.assertj.core.api.Assertions.assertThat;

class QueueTokenTest {

    static final String SECRET = "nova-test-current-secret-0123456789";
    static final Instant NOW = Instant.parse("2026-10-01T01:00:07Z");

    private final QueueToken queueToken = QueueToken.of(SECRET, List.of(), null);
    private final AdmissionTicket ticket = AdmissionTicket.of(SECRET, List.of(), null);

    @Test
    void 대기_토큰은_발급받은_회원과_상품에만_유효하고_창_시작에서_한_시간_뒤에_만료된다() {
        String token = queueToken.issue(productKey(101), customerId(1024), NOW);
        Instant expiresAt = Instant.ofEpochSecond(NOW.getEpochSecond() / QueueToken.WINDOW_SEC * QueueToken.WINDOW_SEC
                + QueueToken.TTL_SEC);

        assertThat(customerOf(token, productKey(101), NOW)).contains(customerId(1024));
        assertThat(customerOf(token, productKey(202), NOW)).isEmpty();
        assertThat(customerOf(token, productKey(101), expiresAt.minusSeconds(1))).contains(customerId(1024));
        assertThat(customerOf(token, productKey(101), expiresAt)).isEmpty();
        assertThat(queueToken.verify(token, productKey(101), NOW)).map(SignedToken.Holder::expiresAt)
                .contains(expiresAt);
    }

    @Test
    void 남은_수명이_절반_아래로_내려가야_새_토큰을_주고_새_토큰은_다시_바꾸지_않는다() {
        String token = queueToken.issue(productKey(101), customerId(1024), NOW);
        SignedToken.Holder holder = queueToken.verify(token, productKey(101), NOW).orElseThrow();
        Instant half = holder.expiresAt().minusSeconds(QueueToken.RENEW_BELOW_SEC);

        assertThat(queueToken.renewal(productKey(101), holder, NOW)).isEmpty();
        assertThat(queueToken.renewal(productKey(101), holder, half)).isEmpty();

        Instant later = half.plusSeconds(1);
        String renewed = queueToken.renewal(productKey(101), holder, later).orElseThrow();
        SignedToken.Holder next = queueToken.verify(renewed, productKey(101), later).orElseThrow();

        assertThat(next.customerId()).isEqualTo(customerId(1024));
        assertThat(next.expiresAt()).isAfter(holder.expiresAt());
        assertThat(queueToken.renewal(productKey(101), next, later)).isEmpty();
        // 옛 토큰은 만료 전까지 그대로 되고, 그 뒤에는 새 토큰만 된다
        assertThat(customerOf(token, productKey(101), holder.expiresAt().minusSeconds(1))).contains(customerId(1024));
        assertThat(customerOf(token, productKey(101), holder.expiresAt())).isEmpty();
        assertThat(customerOf(renewed, productKey(101), holder.expiresAt())).contains(customerId(1024));
    }

    @Test
    void 대기_토큰과_입장권은_같은_비밀이어도_서로_통하지_않는다() {
        String queued = queueToken.issue(productKey(101), customerId(1024), NOW);
        String admitted = ticket.issue(productKey(101), customerId(1024), NOW);

        assertThat(ticket.verify(queued, productKey(101), NOW)).isEmpty();
        assertThat(queueToken.verify(admitted, productKey(101), NOW)).isEmpty();
    }

    private Optional<String> customerOf(String token, String productKey, Instant now) {
        return queueToken.verify(token, productKey, now).map(SignedToken.Holder::customerId);
    }

    @Test
    void 이탈_기록은_입장권_수명보다_오래_남는다() {
        assertThat(GraceRetention.seconds(30)).isGreaterThan(30);
        assertThat(GraceRetention.seconds(120)).isGreaterThan(120);
        assertThat(GraceRetention.seconds(600)).isGreaterThan(600);
    }
}
