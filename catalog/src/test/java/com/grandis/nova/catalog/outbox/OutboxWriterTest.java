package com.grandis.nova.catalog.outbox;

import com.grandis.nova.catalog.registration.InStockProductRegistered;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 아웃박스 기록은 업무 트랜잭션 안에서만 — 따로 커밋되면 업무가 롤백돼도 메시지가 남는다. */
@CatalogIntegrationTest
class OutboxWriterTest {

    @Autowired OutboxWriter writer;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 거절한다(MANDATORY)")
    void requiresTransaction() {
        assertThatThrownBy(() -> writer.append(message(-101L))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count(-101L)).isZero();
    }

    @Test
    @DisplayName("업무 트랜잭션이 롤백되면 메시지도 남지 않고, 커밋되면 봉투 칸이 채워진 행 하나가 남는다")
    void rollsBackWithTheBusinessTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            writer.append(message(-102L));
            status.setRollbackOnly();
        });
        assertThat(count(-102L)).as("롤백").isZero();

        transaction.executeWithoutResult(status -> writer.append(message(-103L)));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT event_id, aggregate_type, event_type, JSON_EXTRACT(payload, '$.items[0].stockTotal') AS stock, "
                        + "JSON_CONTAINS_PATH(payload, 'one', '$.productId') AS has_product_id, publish_attempts "
                        + "FROM catalog_outbox_events WHERE aggregate_id = ?", -103L);
        assertThat((String) row.get("event_id")).hasSize(36);
        assertThat(row).containsEntry("aggregate_type", "PRODUCT").containsEntry("event_type", "IN_STOCK_PRODUCT_REGISTERED")
                .containsEntry("stock", "4");
        assertThat(((Number) row.get("has_product_id")).intValue()).as("상품 id 는 aggregate_id 로만").isZero();
        assertThat(((Number) row.get("publish_attempts")).intValue()).isZero();
    }

    private static InStockProductRegistered message(Long productId) {
        return new InStockProductRegistered(productId, List.of(new InStockProductRegistered.Item(1L, 4)));
    }

    private int count(Long aggregateId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_outbox_events WHERE aggregate_id = ?", Integer.class, aggregateId);
    }
}
