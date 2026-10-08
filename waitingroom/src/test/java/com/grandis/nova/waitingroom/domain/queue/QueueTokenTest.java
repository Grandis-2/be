package com.grandis.nova.waitingroom.domain.queue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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

        assertThat(queueToken.verify(token, productKey(101), NOW)).contains(customerId(1024));
        assertThat(queueToken.verify(token, productKey(202), NOW)).isEmpty();
        assertThat(queueToken.verify(token, productKey(101), expiresAt.minusSeconds(1))).contains(customerId(1024));
        assertThat(queueToken.verify(token, productKey(101), expiresAt)).isEmpty();
    }

    @Test
    void 대기_토큰과_입장권은_같은_비밀이어도_서로_통하지_않는다() {
        String queued = queueToken.issue(productKey(101), customerId(1024), NOW);
        String admitted = ticket.issue(productKey(101), customerId(1024), NOW);

        assertThat(ticket.verify(queued, productKey(101), NOW)).isEmpty();
        assertThat(queueToken.verify(admitted, productKey(101), NOW)).isEmpty();
    }

    @Test
    void 이탈_기록은_입장권_수명보다_오래_남는다() {
        assertThat(GraceRetention.SECONDS).isGreaterThan(AdmissionTicket.TTL_SEC);
    }
}
