package com.grandis.nova.catalog.outbox.publish;

import com.grandis.nova.catalog.outbox.OutboxEventRepository;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/** 보냈는데 발행 완료 표시만 실패할 때 — 그 행은 미발행으로 남아 다시 보내지고, 릴레이는 같은 묶음의 다음 행을 계속 보낸다. */
@CatalogIntegrationTest
class OutboxMarkPublishedFailureTest {

    @MockitoSpyBean OutboxEventRepository outboxEvents;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("표시 실패는 그 행에서 멈추고 다음 행은 보낸다 — 릴레이가 예외로 끊기지 않는다")
    void markFailureDoesNotStopTheBatch() {
        Long failing = insertOld(-401L);
        insertOld(-402L);
        doThrow(new IllegalStateException("db hiccup")).when(outboxEvents).markPublished(eq(failing), any());

        relay.relay();

        assertThat(publishedAt(-401L)).as("보냈지만 표시 못 한 행 — 다시 보내질 수 있게 미발행으로 남는다").isNull();
        assertThat(publishedAt(-402L)).as("같은 묶음의 다음 행").isNotNull();
    }

    private Long insertOld(Long aggregateId) {
        jdbcTemplate.update("""
                INSERT INTO catalog_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, publish_attempts, created_at)
                VALUES (?, 'PRODUCT', ?, 'IN_STOCK_PRODUCT_REGISTERED', JSON_OBJECT('items', JSON_ARRAY()), 0, UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE)
                """, UUID.randomUUID().toString(), aggregateId);
        return jdbcTemplate.queryForObject("SELECT id FROM catalog_outbox_events WHERE aggregate_id = ?", Long.class, aggregateId);
    }

    private Object publishedAt(Long aggregateId) {
        return jdbcTemplate.queryForObject("SELECT published_at FROM catalog_outbox_events WHERE aggregate_id = ?", Object.class, aggregateId);
    }
}
