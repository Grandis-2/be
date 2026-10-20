package com.grandis.nova.common.outbox;

import com.grandis.nova.common.outbox.support.TestOutbox.EventType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/** 같은 목적지 행은 전송 구현이 받는 만큼 묶어 한 번에 보내고, 성공한 행은 한 문장으로 표시한다. 실패는 건마다다. */
class OutboxPublisherBatchTest {

    final OutboxStore store = mock(OutboxStore.class);
    final MessageTransport transport = mock(MessageTransport.class);
    final OutboxPublisher publisher = new OutboxPublisher(store,
            OutboxDefinition.of("it_outbox_events", EventType.values()), transport, new JsonMapper(),
            Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC), OutboxMetrics.NONE);

    @Test
    void 목적지별로_나누고_묶음_크기를_넘으면_다음_묶음으로() {
        given(transport.maxBatchSize()).willReturn(2);
        List<OutboxRow> rows = List.of(row(1, "ITEM_SETTLED"), row(2, "ITEM_NOTIFIED"), row(3, "ITEM_SETTLED"),
                row(4, "ITEM_SETTLED"), row(5, "ITEM_GONE"));

        assertThat(publisher.batches(rows)).extracting(batch -> batch.stream().map(OutboxRow::id).toList())
                .containsExactly(List.of(5L), List.of(1L, 3L), List.of(4L), List.of(2L));
    }

    @Test
    void 묶음은_한_번에_보내고_성공한_행을_한_문장으로_표시한다() {
        given(transport.maxBatchSize()).willReturn(10);
        given(transport.sendAll(anyList())).willReturn(Map.of());
        given(store.markPublished(eq(List.of(1L, 2L, 3L)), any())).willReturn(3);

        int marked = publisher.publishAll(rows(1, 3), null);

        assertThat(marked).isEqualTo(3);
        then(transport).should().sendAll(argThat(messages -> messages.size() == 3));
        then(transport).should(never()).send(any());
    }

    @Test
    void 묶음의_일부가_실패하면_그_행만_실패로_남긴다() {
        given(transport.maxBatchSize()).willReturn(10);
        given(transport.sendAll(anyList())).willReturn(Map.of(1, new IllegalStateException("throttled")));
        given(store.markPublished(eq(List.of(1L, 3L)), any())).willReturn(2);

        assertThat(publisher.publishAll(rows(1, 3), null)).isEqualTo(2);

        then(store).should().recordFailure(2L, null);
        then(store).should(times(1)).recordFailure(any(), any());
    }

    @Test
    void 묶음_호출이_실패하면_모두_실패로_남기고_표시하지_않는다() {
        given(transport.maxBatchSize()).willReturn(10);
        given(transport.sendAll(anyList())).willThrow(new IllegalStateException("down"));
        Instant lease = Instant.parse("2026-10-21T00:01:00Z");

        assertThat(publisher.publishAll(rows(1, 3), lease)).isZero();

        LongStream.rangeClosed(1, 3).forEach(id -> then(store).should().recordFailure(id, lease));
        then(store).should(never()).markPublished(anyList(), any());
    }

    @Test
    void 묶지_않는_전송_구현은_하나씩_send_로_보낸다() {
        given(store.markPublished(anyList(), any())).willReturn(1);

        assertThat(publisher.publishAll(rows(1, 2), null)).isEqualTo(2);

        then(transport).should(times(2)).send(any());
        then(transport).should(never()).sendAll(anyList());
    }

    private static List<OutboxRow> rows(long from, long to) {
        return LongStream.rangeClosed(from, to).mapToObj(id -> row(id, "ITEM_SETTLED")).toList();
    }

    private static OutboxRow row(long id, String eventType) {
        return new OutboxRow(id, UUID.randomUUID().toString(), "ITEM", UUID.randomUUID(), eventType, "{}", 0,
                Instant.parse("2026-10-20T23:59:00Z"), null);
    }
}
