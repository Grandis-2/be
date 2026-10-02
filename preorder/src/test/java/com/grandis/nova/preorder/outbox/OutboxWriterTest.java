package com.grandis.nova.preorder.outbox;

import com.grandis.nova.preorder.accept.application.RegisterJobReady;
import com.grandis.nova.preorder.cancel.application.PreorderCancelRequested;
import com.grandis.nova.preorder.event.CancelJobReady;
import com.grandis.nova.preorder.outbox.publish.OutboxAfterCommitPublisher;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PreorderIntegrationTest
@Import(OutboxWriterTest.CommittedEvents.class)
class OutboxWriterTest {

    @Autowired
    OutboxWriter writer;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CommittedEvents committedEvents;

    /** 기록만 본다. 커밋 직후 발행이 끼어들면 published_at 이 비동기로 채워져 단정이 흔들린다. */
    @MockitoBean
    OutboxAfterCommitPublisher afterCommitPublisher;

    long aggregateId;

    @BeforeEach
    void setUp() {
        aggregateId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        committedEvents.received.clear();
    }

    @Test
    void 이벤트_종류_aggregate_payload_를_적고_미발행으로_둔다() {
        Long id = transactionTemplate.execute(status ->
                writer.append(new RegisterJobReady(aggregateId, "9f1c2d3e")).getId());

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT event_id, event_type, aggregate_type, aggregate_id, publish_attempts, published_at,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.preorderId')) AS preorder_id,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.jobType')) AS job_type,
                       JSON_EXTRACT(payload, '$.syncJobId') AS sync_job_id
                  FROM preorder_outbox_events WHERE id = ?
                """, id);

        assertThat(row)
                .containsEntry("event_type", "REGISTER_JOB_READY")
                .containsEntry("aggregate_type", "PREORDER_SYNC_JOB")
                .containsEntry("aggregate_id", aggregateId)
                .containsEntry("publish_attempts", 0)
                .containsEntry("published_at", null)
                .containsEntry("preorder_id", "9f1c2d3e")
                .containsEntry("job_type", "REGISTER");
        assertThat(String.valueOf(row.get("sync_job_id"))).isEqualTo(String.valueOf(aggregateId));
        assertThat((String) row.get("event_id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void 작업_종류는_이벤트가_정한_값으로만_나간다() {
        Long id = transactionTemplate.execute(status ->
                writer.append(new CancelJobReady(aggregateId, "9f1c2d3e")).getId());

        assertThat(jdbcTemplate.queryForMap("""
                SELECT event_type, JSON_UNQUOTE(JSON_EXTRACT(payload, '$.jobType')) AS job_type
                  FROM preorder_outbox_events WHERE id = ?
                """, id))
                .containsEntry("event_type", "CANCEL_JOB_READY")
                .containsEntry("job_type", "CANCEL");
    }

    @Test
    void 예약_내부_id_는_봉투의_aggregate_로만_쓰고_payload_에는_싣지_않는다() {
        Long id = transactionTemplate.execute(status -> writer.append(
                new PreorderCancelRequested(aggregateId, "9f1c2d3e", 1024L, CancelReason.EXPIRY, 3L)).getId());

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT aggregate_type, aggregate_id, JSON_KEYS(payload) AS payload_keys,
                       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reason')) AS reason
                  FROM preorder_outbox_events WHERE id = ?
                """, id);

        assertThat(row).containsEntry("aggregate_type", "PREORDER")
                .containsEntry("aggregate_id", aggregateId)
                .containsEntry("reason", "EXPIRY");
        assertThat((String) row.get("payload_keys"))
                .contains("preorderId", "customerId", "reason", "cancelSequence")
                .doesNotContain("preorderInternalId", "eventType", "aggregateId");
    }

    @Test
    void 커밋되면_커밋_뒤에_적은_행을_알린다() {
        Long id = transactionTemplate.execute(status ->
                writer.append(new RegisterJobReady(aggregateId, "9f1c2d3e")).getId());

        assertThat(committedEvents.received).containsExactly(new OutboxAppended(id));
    }

    @Test
    void 업무가_롤백되면_행도_알림도_남지_않는다() {
        transactionTemplate.executeWithoutResult(status -> {
            writer.append(new RegisterJobReady(aggregateId, "9f1c2d3e"));
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM preorder_outbox_events WHERE aggregate_id = ?", Integer.class, aggregateId)).isZero();
        assertThat(committedEvents.received).isEmpty();
    }

    @Test
    void 트랜잭션_밖에서는_적을_수_없다() {
        assertThatThrownBy(() -> writer.append(new RegisterJobReady(aggregateId, "9f1c2d3e")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** 발행기 자리. 커밋 뒤에 받은 알림을 모은다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class CommittedEvents {

        final List<OutboxAppended> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(OutboxAppended event) {
            received.add(event);
        }
    }
}
