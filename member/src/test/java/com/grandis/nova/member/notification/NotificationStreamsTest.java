package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 연결 목록의 규칙 중 서버 없이 볼 수 있는 것 — 수명 계산 · 순번 · 연결마다 따로 쓰기. 연결 · 끊김 · 종료는 NotificationStreamTest · NotificationStreamShutdownTest 가 본다. */
@DisplayName("실시간 연결 목록 — 수명 · 순번 · 연결마다 따로 쓰기")
class NotificationStreamsTest {

    static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");
    static final Duration WAIT = Duration.ofSeconds(2);

    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final NotificationStreams streams = new NotificationStreams(
            new NotificationStreamProperties(Duration.ofSeconds(25), Duration.ofMinutes(30), 5, 5000, Duration.ofSeconds(2), Duration.ofSeconds(10)),
            Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);

    @Test
    @DisplayName("수명은 최대 수명과 토큰이 남은 시간 중 짧은 쪽 — 이미 만료됐으면 1ms(0 은 서블릿에서 만료 없음)")
    void lifetimeIsCappedByTokenExpiry() {
        assertThat(streams.lifetime(null)).isEqualTo(Duration.ofMinutes(30));
        assertThat(streams.lifetime(NOW.plus(Duration.ofHours(2)))).isEqualTo(Duration.ofMinutes(30));
        assertThat(streams.lifetime(NOW.plus(Duration.ofMinutes(10)))).isEqualTo(Duration.ofMinutes(10));
        assertThat(streams.lifetime(NOW)).isEqualTo(Duration.ofMillis(1));
        assertThat(streams.lifetime(NOW.minusSeconds(1))).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("연결은 첫 수를 받기 전까지 기다리는 것으로 남고, 이미 넘긴 것보다 큰 순번의 수만 쓴다")
    void sendsOnlyNewerSequence() {
        UUID customer = UUID.randomUUID();
        RecordingEmitter emitter = new RecordingEmitter(null);
        streams.register(customer, emitter);
        assertThat(streams.awaitingFirstCount()).containsExactly(customer);
        long older = streams.nextSequence();
        long newer = streams.nextSequence();

        streams.push(customer, newer, 3);
        await().atMost(WAIT).until(() -> emitter.counts().equals(List.of(3L)));
        streams.push(customer, older, 2);
        streams.push(customer, newer, 3);
        streams.push(customer, streams.nextSequence(), 4);

        await().atMost(WAIT).until(() -> emitter.counts().equals(List.of(3L, 4L)));
        assertThat(streams.awaitingFirstCount()).isEmpty();
    }

    @Test
    @DisplayName("쓰기가 막힌 연결 하나가 다른 연결의 수 · 주기 신호를 늦추지 않고, 막힌 동안 온 수는 가장 새 것만 이어 쓴다")
    void stuckWriteDoesNotDelayOthers() {
        CountDownLatch release = new CountDownLatch(1);
        UUID stuck = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        RecordingEmitter stuckEmitter = new RecordingEmitter(release);
        RecordingEmitter healthyEmitter = new RecordingEmitter(null);
        streams.register(stuck, stuckEmitter);
        streams.register(healthy, healthyEmitter);

        assertTimeoutPreemptively(WAIT, () -> {
            streams.push(stuck, streams.nextSequence(), 1);
            await().atMost(WAIT).until(() -> stuckEmitter.attempts.size() == 1);
            streams.push(healthy, streams.nextSequence(), 1);
            await().atMost(WAIT).until(() -> healthyEmitter.sent.size() == 1);
            streams.push(stuck, streams.nextSequence(), 2);
            streams.push(stuck, streams.nextSequence(), 3);
        });
        // 쓰는 중이면 주기 신호는 건너뛴다 — 성한 연결의 쓰기 표시가 내려가는 순간과 겹칠 수 있어 신호가 닿을 때까지 다시 보낸다
        await().atMost(WAIT).until(() -> {
            streams.beat();
            return healthyEmitter.sent.size() >= 2;
        });
        assertThat(healthyEmitter.counts()).containsExactly(1L);
        assertThat(stuckEmitter.sent).as("막힌 동안은 아무것도 끝나지 않는다").isEmpty();

        release.countDown();

        await().atMost(WAIT).until(() -> stuckEmitter.counts().equals(List.of(1L, 3L)));
        assertThat(meterRegistry.counter(NotificationStreams.PUSH_METRIC, "outcome", "skipped").count()).isEqualTo(1);
        assertThat(meterRegistry.counter(NotificationStreams.PUSH_METRIC, "outcome", "sent").count()).isEqualTo(3);
    }

    @Test
    @DisplayName("이미 닫힌 연결(원인 없는 IllegalStateException)에 쓰다 난 예외는 실패로 세지 않고 연결을 닫는다")
    void writeToCompletedConnectionClosesWithoutCountingFailure() {
        streams.register(UUID.randomUUID(), new ThrowingEmitter(new IllegalStateException("ResponseBodyEmitter has already completed")));

        streams.awaitingFirstCount().forEach(customerId -> streams.push(customerId, streams.nextSequence(), 1));

        await().atMost(WAIT).until(() -> streams.size() == 0);
        assertThat(meterRegistry.counter(NotificationStreams.PUSH_METRIC, "outcome", "failed").count()).isZero();
    }

    @Test
    @DisplayName("원인이 있는 예외(Spring 이 감싼 직렬화 오류 등)는 실패로 세고 연결을 닫는다")
    void unexpectedWriteFailureClosesAndCountsFailure() {
        streams.register(UUID.randomUUID(), new ThrowingEmitter(new IllegalStateException("Failed to send", new RuntimeException("직렬화"))));

        streams.awaitingFirstCount().forEach(customerId -> streams.push(customerId, streams.nextSequence(), 1));

        await().atMost(WAIT).until(() -> streams.size() == 0);
        assertThat(meterRegistry.counter(NotificationStreams.PUSH_METRIC, "outcome", "failed").count()).isEqualTo(1);
    }

    static final class ThrowingEmitter extends SseEmitter {

        private final RuntimeException failure;

        ThrowingEmitter(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void send(SseEventBuilder builder) {
            throw failure;
        }
    }

    /** 쓴 이벤트를 모은다. release 가 있으면 첫 쓰기를 그것이 풀릴 때까지 붙잡는다(버퍼가 찬 클라이언트). */
    static final class RecordingEmitter extends SseEmitter {

        final List<String> attempts = new CopyOnWriteArrayList<>();
        final List<String> sent = new CopyOnWriteArrayList<>();
        private final CountDownLatch release;

        RecordingEmitter(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void send(SseEventBuilder builder) {
            String text = builder.build().stream().map(part -> String.valueOf(part.getData())).collect(Collectors.joining());
            attempts.add(text);
            if (release != null && attempts.size() == 1) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            sent.add(text);
        }

        List<Long> counts() {
            return sent.stream().filter(text -> text.contains("UnreadCount"))
                    .map(text -> Long.parseLong(text.replaceAll("(?s).*count=(\\d+).*", "$1"))).toList();
        }
    }
}
