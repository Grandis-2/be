package com.grandis.nova.common.outbox;

import org.junit.jupiter.api.Test;
import com.grandis.nova.common.outbox.support.TestOutbox.EventType;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * 본문 밖 추가 속성. 서비스가 정하지 않으면 아무것도 싣지 않고(기존 서비스 동작), 정하면 행의 종류 · payload 로 만든 값을 싣는다.
 * 릴레이도 같은 행으로 다시 만들므로 커밋 직후 발행과 재발행의 속성이 같다.
 */
class OutboxPublisherAttributesTest {

    final OutboxStore store = mock(OutboxStore.class);
    final MessageTransport transport = mock(MessageTransport.class);
    final OutboxRow row = new OutboxRow(7L, UUID.randomUUID().toString(), "ITEM", UUID.randomUUID(), "ITEM_SETTLED",
            "{\"actor\":\"ADMIN\"}", 0, Instant.parse("2026-10-10T23:59:00Z"), null);

    @Test
    void 서비스가_정하지_않으면_추가_속성_없이_보낸다() {
        given(store.markPublished(eq(List.of(7L)), any())).willReturn(1);

        assertThat(publisher(null).publish(row, null)).isTrue();

        assertThat(sent().attributes()).isEmpty();
    }

    @Test
    void 서비스가_정한_속성을_행의_종류와_payload_로_만들어_싣는다() {
        given(store.markPublished(eq(List.of(7L)), any())).willReturn(1);
        OutboundAttributes attributes = (eventType, payload) ->
                Map.of("actor", payload.get("actor").asString(), "kind", eventType);

        assertThat(publisher(attributes).publish(row, null)).isTrue();

        OutboundMessage message = sent();
        assertThat(message.attributes()).isEqualTo(Map.of("actor", "ADMIN", "kind", "ITEM_SETTLED"));
        assertThat(message.body()).as("본문은 그대로다").contains("\"payload\":{\"actor\":\"ADMIN\"}");
    }

    /** 속성을 못 만든 메시지를 속성 없이 보내면 받는 쪽이 다르게 처리한다 — 보내지 않고 실패로 남겨 드러낸다. */
    @Test
    void 속성을_만들다_실패하면_보내지_않고_발행_실패로_남긴다() {
        OutboundAttributes failing = (eventType, payload) -> {
            throw new IllegalStateException("boom");
        };

        assertThat(publisher(failing).publish(row, null)).isFalse();

        then(transport).should(never()).send(any());
        then(store).should().recordFailure(7L, null);
        then(store).should(never()).markPublished(any(), any());
    }

    private OutboxPublisher publisher(OutboundAttributes attributes) {
        OutboxDefinition definition = OutboxDefinition.of("it_outbox_events", EventType.values());
        Clock clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);
        return attributes == null
                ? new OutboxPublisher(store, definition, transport, new JsonMapper(), clock, OutboxMetrics.NONE)
                : new OutboxPublisher(store, definition, transport, new JsonMapper(), clock, OutboxMetrics.NONE,
                        attributes);
    }

    private OutboundMessage sent() {
        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);
        then(transport).should().send(captor.capture());
        return captor.getValue();
    }
}
