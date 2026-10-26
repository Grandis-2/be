package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import com.grandis.nova.member.adminalert.AdminAlertRules.Severity;
import com.grandis.nova.member.adminalert.AdminAlertRules.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 연결 목록의 규칙 중 서버 없이 볼 수 있는 것 — 수명 · 첫 unread · 쌓아 쓰기 · 넘치면 닫기 · 실패 · 교체 · 종료 뒤 거절. */
@DisplayName("관리자 알림 실시간 연결 목록 — 수명 · 쌓아 쓰기 · 닫는 까닭")
class AdminAlertStreamsTest {

    static final Instant NOW = Instant.parse("2026-10-26T05:00:00Z");
    static final Duration WAIT = Duration.ofSeconds(2);

    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final AdminAlertStreams streams = new AdminAlertStreams(
            new AdminAlertStreamProperties(Duration.ofSeconds(25), Duration.ofMinutes(30), 2, 3, 3, Duration.ofSeconds(2),
                    Duration.ofSeconds(10)),
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
    @DisplayName("첫 unread 를 받기 전까지 기다리는 연결로 남고, 바뀐 항목이 없으면 이미 받은 연결에는 보내지 않는다. 탭이 맞는 항목만 보낸다")
    void firstUnreadAndAudience() {
        RecordingEmitter admin = new RecordingEmitter(null);
        streams.register("admin", Audience.ADMIN, admin);
        assertThat(streams.awaitingFirst()).isTrue();

        streams.publish(List.of(), unread());
        await().atMost(WAIT).until(() -> admin.sent.size() == 1);
        assertThat(streams.awaitingFirst()).isFalse();

        streams.publish(List.of(), unread());
        streams.publish(List.of(item(Audience.DEVELOPER), item(Audience.ADMIN)), unread());
        await().atMost(WAIT).until(() -> admin.sent.size() == 3);
        assertThat(admin.names()).containsExactly("unread", "notification", "unread");
    }

    @Test
    @DisplayName("쓰기가 막힌 동안 온 사건은 버리지 않고 차례대로 쌓았다가, 풀리면 온 순서 그대로 이어 쓴다")
    void queuesInOrderWhileStuck() {
        CountDownLatch release = new CountDownLatch(1);
        RecordingEmitter stuck = new RecordingEmitter(release);
        streams.register("admin", null, stuck);
        AdminAlertResponse first = item(Audience.ADMIN);

        streams.publish(List.of(), unread());
        await().atMost(WAIT).until(() -> stuck.attempts.size() == 1);
        streams.publish(List.of(first), unread());
        assertThat(stuck.sent).as("막힌 동안은 아무것도 끝나지 않는다").isEmpty();

        release.countDown();

        await().atMost(WAIT).until(() -> stuck.sent.size() == 3);
        assertThat(stuck.names()).containsExactly("unread", "notification", "unread");
        assertThat(stuck.sent.get(1)).contains(first.notificationId().toString());
        assertThat(streams.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("쌓인 것이 max-pending(시험 3)을 넘으면 닫고 overflow 로 한 번만 센다")
    void closesOnOverflow() {
        CountDownLatch release = new CountDownLatch(1);
        RecordingEmitter stuck = new RecordingEmitter(release);
        streams.register("admin", null, stuck);

        streams.publish(List.of(), unread());
        await().atMost(WAIT).until(() -> stuck.attempts.size() == 1);
        streams.publish(List.of(item(Audience.ADMIN)), unread());
        assertThat(streams.size()).as("쌓인 2개 — 아직 상한 안").isEqualTo(1);
        streams.publish(List.of(item(Audience.ADMIN)), unread());

        await().atMost(WAIT).until(() -> streams.size() == 0);
        release.countDown();
        await().atMost(WAIT).until(() -> stuck.completed);
        assertThat(meterRegistry.counter(AdminAlertStreams.CLOSED_METRIC, "reason", "overflow").count()).isEqualTo(1);
        assertThat(meterRegistry.find(AdminAlertStreams.CLOSED_METRIC).counters()).hasSize(1);
    }

    @Test
    @DisplayName("쓰다 IOException(떠난 클라이언트)은 disconnected, 원인이 있는 예외는 error, 이미 닫힌 연결(원인 없는 IllegalStateException)은 세지 않는다")
    void writeFailures() {
        streams.register("admin", null, new ThrowingEmitter(new IOException("broken pipe")));
        streams.publish(List.of(), unread());
        await().atMost(WAIT).until(() -> streams.size() == 0);
        assertThat(meterRegistry.counter(AdminAlertStreams.CLOSED_METRIC, "reason", "disconnected").count()).isEqualTo(1);

        streams.register("ops", null, new ThrowingEmitter(new IllegalStateException("Failed to send", new RuntimeException("직렬화"))));
        streams.publish(List.of(), unread());
        await().atMost(WAIT).until(() -> streams.size() == 0);
        assertThat(meterRegistry.counter(AdminAlertStreams.CLOSED_METRIC, "reason", "error").count()).isEqualTo(1);

        streams.register("admin", null, new ThrowingEmitter(new IllegalStateException("ResponseBodyEmitter has already completed")));
        streams.publish(List.of(), unread());
        await().atMost(WAIT).until(() -> streams.size() == 0);
        assertThat(meterRegistry.find(AdminAlertStreams.CLOSED_METRIC).counters()).hasSize(2);
        assertThat(meterRegistry.counter(AdminAlertStreams.CLOSED_METRIC, "reason", "disconnected").count()
                + meterRegistry.counter(AdminAlertStreams.CLOSED_METRIC, "reason", "error").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 계정의 연결이 상한(시험 2)을 넘으면 가장 오래된 것에 replaced 를 쓰고 닫으며 replaced 로 센다 — 인스턴스 상한(시험 3)에 걸려도 받는다")
    void replacesOldestOfSamePrincipal() {
        RecordingEmitter oldest = new RecordingEmitter(null);
        streams.register("admin", null, oldest);
        streams.register("admin", null, new RecordingEmitter(null));
        streams.register("ops", null, new RecordingEmitter(null));

        streams.register("admin", null, new RecordingEmitter(null));

        assertThat(streams.size()).isEqualTo(3);
        await().atMost(WAIT).until(() -> oldest.completed);
        assertThat(oldest.names()).containsExactly("replaced");
        assertThat(oldest.sent.getFirst()).contains("MAX_PER_PRINCIPAL");
        assertThat(meterRegistry.counter(AdminAlertStreams.CLOSED_METRIC, "reason", "replaced").count()).isEqualTo(1);
        assertThatThrownBy(() -> streams.register("other", null, new RecordingEmitter(null)))
                .hasMessageContaining("가득 찼다");
    }

    @Test
    @DisplayName("종료하면 열린 연결을 모두 닫고, 그 뒤의 연결은 503 이다")
    void stopClosesAllAndRejectsNew() {
        streams.start();
        RecordingEmitter open = new RecordingEmitter(null);
        streams.register("admin", null, open);

        streams.stop();

        assertThat(streams.size()).isZero();
        await().atMost(WAIT).until(() -> open.completed);
        assertThatThrownBy(() -> streams.register("admin", null, new RecordingEmitter(null))).hasMessageContaining("종료 중");
    }

    static Map<Audience, AdminAlertResponse.UnreadCounts> unread() {
        Map<Audience, AdminAlertResponse.UnreadCounts> counts = new EnumMap<>(Audience.class);
        counts.put(Audience.ADMIN, new AdminAlertResponse.UnreadCounts(0, 0, 0));
        counts.put(Audience.DEVELOPER, new AdminAlertResponse.UnreadCounts(0, 0, 0));
        return counts;
    }

    static AdminAlertResponse item(Audience audience) {
        return new AdminAlertResponse(UUID.randomUUID(), "REGISTER_FAILED", audience, Severity.URGENT, Status.OPEN,
                new AdminAlertResponse.Target("NONE", null, null), null, true, NOW, NOW, NOW, null, null);
    }

    static final class ThrowingEmitter extends SseEmitter {

        private final Exception failure;

        ThrowingEmitter(Exception failure) {
            this.failure = failure;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failure instanceof IOException io) {
                throw io;
            }
            throw (RuntimeException) failure;
        }
    }

    /** 쓴 이벤트를 모은다. release 가 있으면 첫 쓰기를 그것이 풀릴 때까지 붙잡는다(버퍼가 찬 클라이언트). */
    static final class RecordingEmitter extends SseEmitter {

        final List<String> attempts = new CopyOnWriteArrayList<>();
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile boolean completed;
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

        @Override
        public void complete() {
            completed = true;
        }

        List<String> names() {
            return sent.stream().map(text -> text.replaceAll("(?s)^event:(\\w+).*", "$1")).toList();
        }
    }
}
