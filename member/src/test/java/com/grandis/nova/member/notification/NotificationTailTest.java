package com.grandis.nova.member.notification;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 주기 하나의 규칙 중 DB 없이 볼 수 있는 것. 주기 스레드 없이 tick() 을 직접 부른다. 실제 표 위의 흐름은 NotificationStreamTest 가 본다. */
@DisplayName("안 읽은 수 세기 — 연결 없으면 읽지 않고, 실패해도 다음 주기에 다시 센다")
class NotificationTailTest {

    final NotificationStreams streams = mock(NotificationStreams.class);
    final NotificationTailQueries queries = mock(NotificationTailQueries.class);

    NotificationTail tail() {
        return new NotificationTail(streams, queries,
                new NotificationStreamProperties(Duration.ofSeconds(25), Duration.ofMinutes(30), 5, 5000, Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("이 인스턴스에 연결이 없으면 DB 를 읽지 않는다")
    void noQueryWithoutConnections() {
        when(streams.size()).thenReturn(0);
        NotificationTail tail = tail();

        tail.tick();
        tail.tick();

        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("첫 수를 기다리는 회원을 500명씩 한 번에 세고, 수가 없는 회원은 0 이다")
    void countsWaitingCustomersInChunks() {
        Set<UUID> waiting = new HashSet<>(IntStream.range(0, 501).mapToObj(i -> UUID.randomUUID()).toList());
        UUID none = waiting.iterator().next();
        when(streams.size()).thenReturn(waiting.size());
        when(streams.awaitingFirstCount()).thenReturn(waiting);
        when(streams.nextSequence()).thenReturn(1L, 2L);
        when(queries.unreadCounts(anyCollection())).thenReturn(Map.of());

        tail().tick();

        verify(queries, times(2)).unreadCounts(anyCollection());
        verify(streams).push(eq(none), anyLong(), eq(0L));
    }

    @Test
    @DisplayName("한 주기가 DB 오류로 실패해도 다음 주기에 다시 세어 보낸다")
    void failedTickIsRetried() {
        UUID customer = UUID.randomUUID();
        when(streams.size()).thenReturn(1);
        when(streams.awaitingFirstCount()).thenReturn(Set.of(customer));
        when(streams.nextSequence()).thenReturn(7L, 8L);
        when(queries.unreadCounts(anyCollection()))
                .thenThrow(new DataAccessResourceFailureException("DB 연결 끊김"))
                .thenReturn(Map.of(customer, 3L));
        NotificationTail tail = tail();

        tail.tick();
        tail.tick();

        verify(streams).push(customer, 8L, 3L);
    }
}
