package com.grandis.nova.catalog.outbox.publish;

import com.grandis.nova.catalog.outbox.OutboxWriter;
import com.grandis.nova.catalog.registration.InStockProductRegistered;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** 전송이 늘 실패할 때 — 행은 미발행으로 남고 실패 횟수가 오르며, 릴레이가 건 리스는 풀려 다음 주기에 다시 가져간다. */
@CatalogIntegrationTest
class OutboxPublishFailureTest {

    @MockitoBean MessageTransport transport;
    @Autowired OutboxWriter writer;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("커밋 직후 발행이 실패하면 업무는 커밋된 채 행이 미발행으로 남고 실패 횟수만 오른다")
    void afterCommitFailureLeavesRowForRelay() {
        doThrow(new IllegalStateException("queue down")).when(transport).send(any());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                writer.append(new InStockProductRegistered(-301L, List.of(new InStockProductRegistered.Item(1L, 1)))));

        Map<String, Object> row = await().atMost(Duration.ofSeconds(5)).until(() -> jdbcTemplate.queryForMap(
                "SELECT published_at, publish_attempts FROM catalog_outbox_events WHERE aggregate_id = ?", -301L),
                attempted -> ((Number) attempted.get("publish_attempts")).intValue() > 0);
        assertThat(row.get("published_at")).isNull();
        assertThat(((Number) row.get("publish_attempts")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("릴레이 전송이 실패하면 실패 횟수가 오르고 자기 리스를 풀어 다음 주기에 다시 가져갈 수 있게 한다")
    void relayFailureReleasesItsLease() {
        doThrow(new IllegalStateException("queue down")).when(transport).send(any());
        jdbcTemplate.update("""
                INSERT INTO catalog_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, publish_attempts, created_at)
                VALUES (?, 'PRODUCT', -311, 'IN_STOCK_PRODUCT_REGISTERED', JSON_OBJECT('items', JSON_ARRAY()), 0,
                        UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE)
                """, UUID.randomUUID().toString());

        relay.relay();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT published_at, publish_attempts, lease_until FROM catalog_outbox_events WHERE aggregate_id = -311");
        assertThat(row.get("published_at")).isNull();
        assertThat(((Number) row.get("publish_attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("lease_until")).as("리스를 풀었다 — 1분을 기다리지 않고 다음 주기에 다시 가져간다").isNull();
    }
}
