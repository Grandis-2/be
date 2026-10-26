package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import com.grandis.nova.member.adminalert.AdminAlertRules.Severity;
import com.grandis.nova.member.adminalert.AdminAlertRules.Source;
import com.grandis.nova.member.adminalert.AdminAlertRules.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주기 하나의 규칙 — 표는 메모리로, 시계는 손으로 돌리고 tick() 을 직접 부른다(회원 알림의 꼬리 읽기 시험과 같은 방식).
 * 실제 표 · 연결 위의 흐름은 AdminAlertStreamTest 가 본다.
 */
@DisplayName("관리자 알림 꼬리 읽기 — 겹침 · 중복 거르기 · 실패 뒤 다시 읽기 · 연결 없으면 읽지 않기")
class AdminAlertTailTest {

    static final Instant T0 = Instant.parse("2026-10-26T05:00:00Z");

    final AdminAlertStreams streams = mock(AdminAlertStreams.class);
    final MemoryTable table = new MemoryTable();
    final AdminAlertService service = mock(AdminAlertService.class);
    final AdminAlertWriterTest.MutableClock clock = new AdminAlertWriterTest.MutableClock(T0);
    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final AdminAlertTail tail = new AdminAlertTail(streams, table, service, new AdminAlertStreamProperties(Duration.ofSeconds(25),
            Duration.ofMinutes(30), 20, 100, 1000, 100, Duration.ofSeconds(2), Duration.ofSeconds(10)), clock, meterRegistry);

    @BeforeEach
    void setUp() {
        when(service.response(any())).thenAnswer(call -> {
            AdminAlertQueries.View view = call.getArgument(0);
            return new AdminAlertResponse(view.id(), view.type(), view.audience(), view.severity(), view.status(),
                    new AdminAlertResponse.Target("NONE", null, null), null, true, view.firstOccurredAt(), view.lastUpdatedAt(),
                    view.raisedAt(), view.resolvedAt(), view.readAt());
        });
        when(service.unreadCounts()).thenReturn(AdminAlertStreamsTest.unread());
    }

    @Test
    @DisplayName("이 인스턴스에 연결이 없으면 DB 를 읽지 않고 주기 시간도 재지 않는다")
    void noQueryWithoutConnections() {
        when(streams.size()).thenReturn(0);

        tail.tick();
        tail.tick();

        assertThat(table.pages).isZero();
        assertThat(meterRegistry.timer(AdminAlertTail.TIMER).count()).isZero();
    }

    @Test
    @DisplayName("겹침 창 안에서 다시 읽은 행은 같은 version 이면 다시 보내지 않고, 바뀌었으면(숫자 갱신 · 해소 · 읽음) 다시 보낸다")
    void rereadIsFilteredButChangesAreSent() {
        when(streams.size()).thenReturn(1);
        UUID id = UUID.randomUUID();
        table.put(id, T0.minusSeconds(1));

        tail.tick();
        clock.advance(Duration.ofSeconds(2));
        tail.tick();
        verify(streams, times(1)).publish(anyList(), anyMap());

        table.put(id, T0.plusSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        tail.tick();
        verify(streams, times(2)).publish(anyList(), anyMap());
    }

    @Test
    @DisplayName("같은 마이크로초에 두 번 바뀐 행도 두 번째 변경을 보낸다 — changed_at 이 같아도 version 이 다르다")
    void secondChangeInTheSameMicrosecondIsSent() {
        when(streams.size()).thenReturn(1);
        UUID id = UUID.randomUUID();
        table.put(id, T0.minusSeconds(1));
        tail.tick();

        table.put(id, T0.minusSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        tail.tick();

        verify(streams, times(2)).publish(anyList(), anyMap());
    }

    @Test
    @DisplayName("한 주기에 바뀐 행이 resync 기준(100)을 넘으면 행마다 보내지 않고 resync 하나를 보낸다 — 기준 이하면 행마다 보낸다")
    void bulkChangeIsOneResync() {
        when(streams.size()).thenReturn(1);
        for (int i = 0; i < 100; i++) {
            table.put(UUID.randomUUID(), T0.minusMillis(500));
        }
        tail.tick();
        verify(streams).publish(anyList(), anyMap());

        for (int i = 0; i < 101; i++) {
            table.put(UUID.randomUUID(), T0.plusMillis(500));
        }
        clock.advance(Duration.ofSeconds(2));
        tail.tick();
        verify(streams).resync(anyMap());
        verify(streams, times(1)).publish(anyList(), anyMap());
    }

    @Test
    @DisplayName("몰린 변경(1,200행)은 쪽(500)을 넘겨 끝까지 읽어 resync 하나로 알리고, 10초가 지나면 창 밖이라 다시 읽지 않는다")
    void burstPagesAndFallsOutOfTheWindow() {
        when(streams.size()).thenReturn(1);
        for (int i = 0; i < 1200; i++) {
            table.put(UUID.randomUUID(), T0.minusMillis(500));
        }

        tail.tick();
        assertThat(table.pages).as("500 · 500 · 200").isEqualTo(3);
        verify(streams).resync(anyMap());
        verify(streams, never()).publish(anyList(), anyMap());

        clock.advance(Duration.ofSeconds(12));
        tail.tick();
        clock.advance(Duration.ofSeconds(2));
        table.pages = 0;
        table.rowsRead = 0;
        tail.tick();
        assertThat(table.rowsRead).isZero();
    }

    @Test
    @DisplayName("한 주기가 DB 오류로 실패하면 자리를 두고 다음 주기에 그 자리부터 다시 읽어 보낸다")
    void failedTickIsRetriedFromTheSamePlace() {
        when(streams.size()).thenReturn(1);
        table.failNext = true;
        tail.tick();
        verify(streams, never()).publish(anyList(), anyMap());

        clock.advance(Duration.ofSeconds(30));
        table.put(UUID.randomUUID(), T0.minusSeconds(5));   // 첫 주기보다 5초 앞의 변경이 이제야 보인다
        tail.tick();

        verify(streams).publish(anyList(), anyMap());
    }

    @Test
    @DisplayName("바뀐 행이 없어도 첫 unread 를 기다리는 연결이 있으면 보낸다")
    void awaitingFirstGetsUnread() {
        when(streams.size()).thenReturn(1);
        when(streams.awaitingFirst()).thenReturn(true);

        tail.tick();

        verify(streams).publish(List.of(), AdminAlertStreamsTest.unread());
    }

    /** changed_at · id 순으로 쪽을 내는 메모리 표. */
    static final class MemoryTable extends AdminAlertQueries {

        final List<AdminAlertQueries.View> rows = new ArrayList<>();
        int pages;
        int rowsRead;
        boolean failNext;

        MemoryTable() {
            super(null);
        }

        /** 넣거나 바꾼다 — 바꿀 때마다 version 이 오른다(표와 같다). */
        void put(UUID id, Instant changedAt) {
            long version = rows.stream().filter(row -> row.id().equals(id)).mapToLong(row -> row.version() + 1).findFirst().orElse(0);
            rows.removeIf(row -> row.id().equals(id));
            rows.add(new AdminAlertQueries.View(id, "REGISTER_FAILED", Audience.ADMIN, Severity.URGENT, Status.OPEN, Source.GRAFANA,
                    "NONE", "", null, "{}", T0, T0, T0, null, null, changedAt, version));
            rows.sort(Comparator.comparing(AdminAlertQueries.View::changedAt).thenComparing(AdminAlertQueries.View::id));
        }

        @Override
        List<AdminAlertQueries.View> changed(Instant at, AdminAlertQueries.View after, int limit) {
            if (failNext) {
                failNext = false;
                throw new DataAccessResourceFailureException("DB 연결 끊김");
            }
            pages++;
            List<AdminAlertQueries.View> page = rows.stream()
                    .filter(row -> after == null ? !row.changedAt().isBefore(at)
                            : row.changedAt().isAfter(after.changedAt())
                            || row.changedAt().equals(after.changedAt()) && row.id().compareTo(after.id()) > 0)
                    .limit(limit).toList();
            rowsRead += page.size();
            return page;
        }
    }
}
