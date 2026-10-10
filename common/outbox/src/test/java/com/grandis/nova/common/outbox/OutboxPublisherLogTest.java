package com.grandis.nova.common.outbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.grandis.nova.common.outbox.support.TestOutbox.EventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

/**
 * 발행 로그는 보낼 때마다 한 줄이다. 이벤트를 찾는 값은 메시지가 아니라 키-값으로 달려야 수집 쪽이 속성으로 옮긴다.
 * payload 는 어디에도 남지 않는다. 키 이름은 OTel 에이전트의 structured-attributes 목록과 맞춘 것이라 바꾸면 이 시험이 깨져야 한다.
 */
class OutboxPublisherLogTest {

    static final String PAYLOAD = "{\"shipTo\":\"서울시 어딘가 101동\"}";

    final OutboxStore store = mock(OutboxStore.class);
    final MessageTransport transport = mock(MessageTransport.class);
    final OutboxPublisher publisher = new OutboxPublisher(store, OutboxDefinition.of("it_outbox_events", EventType.values()),
            transport, new JsonMapper(), Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC), OutboxMetrics.NONE);

    final Logger logger = (Logger) LoggerFactory.getLogger(OutboxPublisher.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    Level previousLevel;

    final OutboxRow row = new OutboxRow(7L, UUID.randomUUID().toString(), "ITEM", UUID.randomUUID(), "ITEM_SETTLED",
            PAYLOAD, 0, Instant.parse("2026-10-10T23:59:00Z"), null);

    @BeforeEach
    void attach() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void 보내면_이벤트를_찾는_값을_키_값으로_단_INFO_한_줄을_남긴다() {
        given(store.markPublished(eq(7L), any())).willReturn(1);

        assertThat(publisher.publish(row, null)).isTrue();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(keyValues(event)).isEqualTo(Map.of(
                    "event_id", row.eventId(),
                    "aggregate_type", "ITEM",
                    "aggregate_id", row.aggregateId().toString(),
                    "event_type", "ITEM_SETTLED",
                    "destination", "test-events"));
            assertThat(event.getFormattedMessage()).doesNotContain(row.eventId()).doesNotContain("shipTo");
            assertThat(keyValues(event).values()).noneMatch(value -> value.contains("shipTo"));
        });
    }

    /** 다른 쪽(릴레이 · 커밋 직후 발행)이 먼저 표시했어도 이쪽도 보냈으므로 한 줄을 남긴다. */
    @Test
    void 이미_표시된_행이어도_보냈으면_남긴다() {
        given(store.markPublished(eq(7L), any())).willReturn(0);

        assertThat(publisher.publish(row, null)).isFalse();

        assertThat(appender.list).singleElement()
                .satisfies(event -> assertThat(keyValues(event)).containsEntry("event_id", row.eventId()));
    }

    @Test
    void 보내지_못하면_event_id_를_단_WARN_한_줄을_남기고_발행_로그는_없다() {
        willThrow(new IllegalStateException("queue down")).given(transport).send(any());

        assertThat(publisher.publish(row, null)).isFalse();

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(keyValues(event))
                    .containsEntry("event_id", row.eventId())
                    .containsEntry("aggregate_type", "ITEM")
                    .containsEntry("aggregate_id", row.aggregateId().toString())
                    .containsEntry("event_type", "ITEM_SETTLED");
            assertThat(event.getFormattedMessage()).contains("outboxEventId=7").doesNotContain("shipTo");
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("queue down");
        });
    }

    private static Map<String, String> keyValues(ILoggingEvent event) {
        Map<String, String> values = new LinkedHashMap<>();
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                values.put(pair.key, String.valueOf(pair.value));
            }
        }
        return values;
    }
}
