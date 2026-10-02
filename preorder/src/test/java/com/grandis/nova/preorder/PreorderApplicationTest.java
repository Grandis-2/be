package com.grandis.nova.preorder;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@PreorderIntegrationTest
class PreorderApplicationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 컨텍스트가_뜨고_세션_격리_수준이_READ_COMMITTED() {
        assertThat(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("READ-COMMITTED");
    }

    @Test
    void 마이그레이션이_적용되어_있다() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'shop'", String.class);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);

        assertThat(tables).contains("preorder_campaigns", "shipment_batches", "preorders", "preorder_events",
                "preorder_sync_jobs", "preorder_sync_attempts", "outbox_events", "flyway_schema_history");
        assertThat(failed).isZero();
    }
}
