package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grandis.nova.common.UuidBinary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 주기 하나의 규칙 — 표는 메모리로, 시계는 손으로 돌리고 tick() 을 직접 부른다(기다림 없이 정해진 결과).
 * 실제 표 · 연결 위의 흐름은 NotificationStreamPushTest 가 본다.
 */
@DisplayName("안 읽은 수 세기 — 읽는 폭 · 실패 뒤 다시 읽기 · 연결 없으면 읽지 않기")
class NotificationTailTest {

    static final Instant T0 = Instant.parse("2026-10-15T03:00:00Z");

    final NotificationStreams streams = mock(NotificationStreams.class);
    final MemoryTable table = new MemoryTable();
    final MutableClock clock = new MutableClock(T0);
    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final NotificationTail tail = new NotificationTail(streams, table, new NotificationStreamProperties(Duration.ofSeconds(25),
            Duration.ofMinutes(30), 5, 5000, Duration.ofSeconds(2), Duration.ofSeconds(10)), clock, meterRegistry);

    @Test
    @DisplayName("이 인스턴스에 연결이 없으면 DB 를 읽지 않고 주기 시간도 재지 않는다")
    void noQueryWithoutConnections() {
        when(streams.size()).thenReturn(0);

        tail.tick();
        tail.tick();

        assertThat(table.pages).isZero();
        assertThat(table.counts).isZero();
        assertThat(meterRegistry.timer(NotificationTail.TIMER).count()).isZero();
    }

    @Test
    @DisplayName("몰린 알림(1,500행)은 쪽을 넘겨 끝까지 읽고, 10초가 지나면 조용한 주기는 창만 읽는다 — 새 알림이 없어도 몰린 행을 다시 읽지 않는다")
    void burstFallsOutOfTheWindow() {
        when(streams.size()).thenReturn(1);
        for (int i = 0; i < 1500; i++) {
            table.add(v7At(T0.minusMillis(500)), UUID.randomUUID());
        }

        tail.tick();
        assertThat(table.rowsRead).isEqualTo(1500);
        assertThat(table.pages).as("500 · 500 · 500 · 0").isEqualTo(4);

        clock.advance(Duration.ofSeconds(12));
        tail.tick();   // 앞 주기 시작 − 10초부터 — 아직 창 안
        clock.advance(Duration.ofSeconds(2));
        table.resetReads();
        tail.tick();

        assertThat(table.rowsRead).isZero();
        assertThat(table.pages).isEqualTo(1);
    }

    @Test
    @DisplayName("첫 주기가 실패해도 그 주기의 자리부터 다시 읽는다 — 그사이 늦게 커밋된 오래된 id 를 놓치지 않는다")
    void failedFirstReadLeavesNoGap() {
        UUID customer = UUID.randomUUID();
        when(streams.size()).thenReturn(1);
        when(streams.connected(anyCollection())).thenReturn(Set.of(customer));
        when(streams.nextSequence()).thenReturn(1L);
        table.failNext = true;

        tail.tick();
        clock.advance(Duration.ofSeconds(30));
        table.add(v7At(T0.minusSeconds(5)), customer);   // 첫 주기보다 5초 앞의 id 가 이제야 보인다
        table.unread.put(customer, 1L);
        tail.tick();

        verify(streams).push(customer, 1L, 1L);
    }

    @Test
    @DisplayName("겹침 창 안에서 다시 읽은 행은 다시 세지 않는다")
    void rereadRowsAreNotCountedAgain() {
        UUID customer = UUID.randomUUID();
        when(streams.size()).thenReturn(1);
        when(streams.connected(anyCollection())).thenAnswer(call -> new HashSet<UUID>(call.getArgument(0)));
        when(streams.nextSequence()).thenReturn(1L, 2L, 3L);
        table.add(v7At(T0.minusSeconds(1)), customer);

        tail.tick();
        clock.advance(Duration.ofSeconds(2));
        tail.tick();

        assertThat(table.counts).isEqualTo(1);
    }

    @Test
    @DisplayName("첫 수를 기다리는 회원과 새 알림의 회원을 합쳐 500명씩 센다 — 수가 없는 회원은 0")
    void countsWaitingAndFreshTogetherInChunks() {
        Set<UUID> waiting = new HashSet<>(IntStream.range(0, 500).mapToObj(i -> UUID.randomUUID()).toList());
        UUID fresh = UUID.randomUUID();
        UUID none = waiting.iterator().next();
        when(streams.size()).thenReturn(501);
        when(streams.awaitingFirstCount()).thenReturn(waiting);
        when(streams.connected(anyCollection())).thenReturn(Set.of(fresh));
        when(streams.nextSequence()).thenReturn(1L, 2L);
        table.add(v7At(T0.minusSeconds(1)), fresh);
        table.unread.put(fresh, 1L);

        tail.tick();

        assertThat(table.counts).as("501명 → 두 번").isEqualTo(2);
        verify(streams).push(eq(none), anyLong(), eq(0L));
        verify(streams).push(eq(fresh), anyLong(), eq(1L));
    }

    @Test
    @DisplayName("한 주기가 DB 오류로 실패해도 다음 주기에 다시 세어 보낸다")
    void failedTickIsRetried() {
        UUID customer = UUID.randomUUID();
        when(streams.size()).thenReturn(1);
        when(streams.awaitingFirstCount()).thenReturn(Set.of(customer));
        when(streams.nextSequence()).thenReturn(7L);
        table.unread.put(customer, 3L);
        table.failNext = true;

        tail.tick();
        tail.tick();

        verify(streams).push(customer, 7L, 3L);
    }

    @Test
    @DisplayName("v7 id 의 시각은 만든 밀리초이고, 그 밀리초의 가장 작은 값은 BINARY(16) 비교에서 그 id 보다 작거나 같다")
    void v7TimeAndFloor() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        Instant at = NotificationTail.timeOf(id);

        assertThat(at).isBetween(before, Instant.now());
        assertThat(compare(NotificationTail.smallestIdAt(at), id)).isLessThanOrEqualTo(0);
        assertThat(compare(NotificationTail.smallestIdAt(at.plusMillis(1)), id)).isGreaterThan(0);
    }

    static int compare(UUID a, UUID b) {
        return Arrays.compareUnsigned(UuidBinary.toBytes(a), UuidBinary.toBytes(b));
    }

    /** 그 시각의 v7 id. 시각 뒤 비트는 무작위(버전 7 · 변형 10). */
    static UUID v7At(Instant at) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new UUID(at.toEpochMilli() << 16 | 0x7000L | random.nextLong(0x1000L),
                random.nextLong() & 0x3FFFFFFFFFFFFFFFL | 0x8000000000000000L);
    }

    /** id 순으로 정렬된 메모리 표. 읽은 쪽 · 행 · 세기 횟수를 센다. failNext 면 다음 읽기 하나를 DB 오류로 끝낸다. */
    static final class MemoryTable extends NotificationTailQueries {

        final List<Row> rows = new ArrayList<>();
        final Map<UUID, Long> unread = new HashMap<>();
        boolean failNext;
        int pages;
        int rowsRead;
        int counts;

        MemoryTable() {
            super(null);
        }

        void add(UUID id, UUID customerId) {
            rows.add(new Row(id, customerId));
            rows.sort(Comparator.comparing(Row::id, NotificationTailTest::compare));
        }

        void resetReads() {
            pages = 0;
            rowsRead = 0;
        }

        @Override
        List<Row> page(UUID bound, boolean inclusive, int limit) {
            if (failNext) {
                failNext = false;
                throw new DataAccessResourceFailureException("DB 연결 끊김");
            }
            pages++;
            List<Row> page = rows.stream()
                    .filter(row -> inclusive ? compare(row.id(), bound) >= 0 : compare(row.id(), bound) > 0)
                    .limit(limit).toList();
            rowsRead += page.size();
            return page;
        }

        @Override
        Map<UUID, Long> unreadCounts(Collection<UUID> customerIds) {
            counts++;
            Map<UUID, Long> found = new HashMap<>();
            customerIds.forEach(id -> {
                if (unread.containsKey(id)) {
                    found.put(id, unread.get(id));
                }
            });
            return found;
        }
    }

    /** 손으로 돌리는 시계. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
