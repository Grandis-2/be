package com.grandis.nova.preorder;

import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PreorderIntegrationTest
class PreorderApplicationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationContext context;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    Campaigns campaigns;

    @Test
    void 컨텍스트가_뜨고_세션_격리_수준이_READ_COMMITTED() {
        assertThat(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("READ-COMMITTED");
    }

    /** 코드 기본값의 드라이버 옵션이 연결까지 닿았다 — 읽기 전용 트랜잭션을 서버에 알리지 않는다. */
    @Test
    void 읽기_전용_트랜잭션을_서버에_알리지_않는다() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnly.setReadOnly(true);

        Integer serverReadOnly = readOnly.execute(
                status -> jdbcTemplate.queryForObject("SELECT @@session.transaction_read_only", Integer.class));

        assertThat(serverReadOnly).isZero();
    }

    /**
     * 드라이버가 세션 · 트랜잭션 상태를 스스로 기억해도(useLocalSessionState · useLocalTransactionState) 실패한 트랜잭션은
     * 롤백되고, 같은 스레드가 다시 받는 연결(Hikari 는 방금 돌려준 연결을 준다)로 이어서 쓰고 커밋할 수 있다.
     */
    @Test
    void 실패한_트랜잭션은_롤백되고_그_연결로_이어서_쓸_수_있다() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        TransactionTemplate readOnly = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> {
            campaigns.countAccepting(Instant.now());
            throw new IllegalStateException("읽기 실패");
        })).hasMessage("읽기 실패");
        AtomicReference<UUID> rolledBack = new AtomicReference<>();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            rolledBack.set(fixtures.openPreorderProduct().productId());
            throw new IllegalStateException("쓰기 실패");
        })).hasMessage("쓰기 실패");
        UUID committed = transactionTemplate.execute(status -> fixtures.openPreorderProduct().productId());

        assertThat(campaigns.exists(rolledBack.get())).as("쓰기가 실패한 트랜잭션은 롤백됐다").isFalse();
        assertThat(campaigns.exists(committed)).as("이어진 트랜잭션은 커밋됐다").isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT @@session.autocommit", Integer.class))
                .as("돌려받은 연결은 자동 커밋으로 돌아와 있다").isEqualTo(1);
    }

    @Test
    void 마이그레이션이_적용되어_있다() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'shop'", String.class);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);

        assertThat(tables).contains("preorder_campaigns", "shipment_batches", "preorders", "preorder_events",
                "preorder_sync_jobs", "preorder_sync_attempts", "preorder_outbox_events", "preorder_payment_anomalies",
                "flyway_schema_history");
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

    /** 주문의 복합 외래 키 대상이던 (id, customer_id) 유일 키는 그 키가 빠져 지웠다 — id 가 PK 라 겹친다. */
    @Test
    void preorders_에는_주문_외래_키용_유일_키가_없다() {
        List<String> uniques = jdbcTemplate.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                 WHERE table_schema = 'shop' AND table_name = 'preorders' AND non_unique = 0
                """, String.class);

        assertThat(uniques).as("대조군 — 활성 예약 유일 키").contains("PRIMARY", "uq_preorder_active");
        assertThat(uniques).doesNotContain("uq_preorder_id_customer");
    }

    /** 꺼지면 기동은 되고 @Scheduled 작업(상태 지표 갱신 등)만 조용히 멈춘다. */
    @Test
    void 스케줄링이_켜져_있다() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
    }
}
