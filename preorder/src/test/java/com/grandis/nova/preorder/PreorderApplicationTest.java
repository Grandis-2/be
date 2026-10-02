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
                "preorder_sync_jobs", "preorder_sync_attempts", "preorder_outbox_events", "flyway_schema_history");
        assertThat(tables).doesNotContain("outbox_events");
        assertThat(failed).isZero();
    }

    /** 아웃박스 표의 인덱스 · CHECK 이름도 표 이름을 따른다. 미발행 인덱스는 릴레이 정렬(publish_attempts, id)과 같다. */
    @Test
    void preorder_아웃박스_표의_인덱스와_CHECK_는_표_이름을_따른다() {
        List<String> unpublished = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.statistics
                 WHERE table_schema = 'shop' AND table_name = 'preorder_outbox_events'
                   AND index_name = 'ix_preorder_outbox_unpublished'
                 ORDER BY seq_in_index
                """, String.class);
        List<String> uniques = jdbcTemplate.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                 WHERE table_schema = 'shop' AND table_name = 'preorder_outbox_events' AND non_unique = 0
                """, String.class);
        List<String> checks = jdbcTemplate.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                 WHERE table_schema = 'shop' AND table_name = 'preorder_outbox_events' AND constraint_type = 'CHECK'
                """, String.class);

        assertThat(unpublished).containsExactly("published_at", "publish_attempts", "id");
        assertThat(uniques).containsExactlyInAnyOrder("PRIMARY", "uq_preorder_outbox_event_id");
        assertThat(checks).containsExactly("ck_preorder_outbox_attempts");
    }
}
