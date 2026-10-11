package com.grandis.nova.common.outbox;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.outbox.support.BusinessRecord;
import com.grandis.nova.common.outbox.support.BusinessRecords;
import com.grandis.nova.common.outbox.support.OutboxIntegrationTest;
import com.grandis.nova.common.outbox.support.TestOutbox.ItemForgotten;
import com.grandis.nova.common.outbox.support.TestOutbox.ItemSettled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 아웃박스 기록과 커밋 후 신호. 커밋 · 롤백을 직접 보려고 테스트 트랜잭션 대신 TransactionTemplate 을 쓴다.
 * 행은 시험마다 새 aggregate id 로 적어 서로 겹치지 않는다.
 */
@OutboxIntegrationTest
@Import(OutboxWriterTest.CommittedEvents.class)
class OutboxWriterTest {

    @Autowired
    OutboxWriter writer;

    @Autowired
    BusinessRecords businessRecords;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    CommittedEvents committedEvents;

    @Autowired
    Clock clock;

    UUID itemId;

    @BeforeEach
    void setUp() {
        itemId = UUID.randomUUID();
        committedEvents.received.clear();
    }

    @Test
    void 미발행_행을_적고_시각은_서비스_시계의_UTC_다() {
        Instant before = clock.instant();
        Long id = transactionTemplate.execute(status -> writer.append(settled()));
        Instant after = clock.instant();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT event_id, event_type, aggregate_type, BIN_TO_UUID(aggregate_id) AS aggregate_id, publish_attempts,
                       lease_until, published_at,
                       DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at_utc
                  FROM it_outbox_events WHERE id = ?
                """, id);

        assertThat(row)
                .containsEntry("event_type", "ITEM_SETTLED")
                .containsEntry("aggregate_type", "ITEM")
                .containsEntry("aggregate_id", itemId.toString())
                .containsEntry("publish_attempts", 0)
                .containsEntry("lease_until", null)
                .containsEntry("published_at", null);
        // JDBC URL 의 시간대와 상관없이 UTC 로 적힌다. 로컬 시각으로 적히면 구간을 벗어난다
        assertThat(Instant.parse((String) row.get("created_at_utc"))).isBetween(before, after);
        assertThat((String) row.get("event_id")).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /** payload 는 record 칸뿐이다. 인터페이스 메서드(종류 · aggregate)와 @JsonIgnore 칸은 실리지 않는다. */
    @Test
    void payload_는_record_칸만_싣는다() {
        Long id = transactionTemplate.execute(status -> writer.append(settled()));

        String payload = jdbcTemplate.queryForObject("SELECT payload FROM it_outbox_events WHERE id = ?", String.class, id);

        assertThat(jsonMapper.readTree(payload).propertyNames()).containsExactlyInAnyOrder("result", "sequence");
    }

    @Test
    void 커밋하면_기록_신호가_한_번_간다() {
        Long id = transactionTemplate.execute(status -> writer.append(settled()));

        assertThat(committedEvents.received).containsExactly(new OutboxAppended(id));
    }

    @Test
    void 롤백하면_행도_신호도_없다() {
        transactionTemplate.executeWithoutResult(status -> {
            writer.append(settled());
            status.setRollbackOnly();
        });

        assertThat(rowsOf(itemId)).isZero();
        assertThat(committedEvents.received).isEmpty();
    }

    /** JDBC 로 적은 행이 업무 트랜잭션(JPA)의 커넥션을 쓴다 — 따로 커밋되면 업무만 롤백되고 메시지는 남는다. */
    @Test
    void JPA_업무_변경이_롤백되면_아웃박스도_함께_롤백된다() {
        String name = "rollback-" + itemId;

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            businessRecords.saveAndFlush(new BusinessRecord(name));
            writer.append(settled());
            throw new IllegalStateException("업무 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(businessRows(name)).isZero();
        assertThat(rowsOf(itemId)).isZero();
        assertThat(committedEvents.received).isEmpty();
    }

    @Test
    void JPA_업무_변경과_아웃박스는_함께_커밋된다() {
        String name = "commit-" + itemId;

        Long id = transactionTemplate.execute(status -> {
            Long appended = writer.append(settled());
            businessRecords.save(new BusinessRecord(name));
            return appended;
        });

        assertThat(businessRows(name)).isOne();
        assertThat(rowsOf(itemId)).isOne();
        assertThat(committedEvents.received).containsExactly(new OutboxAppended(id));
    }

    @Test
    void 트랜잭션_밖에서는_적지_않는다() {
        assertThatThrownBy(() -> writer.append(settled())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rowsOf(itemId)).isZero();
    }

    /** 릴레이가 목적지를 찾지 못해 영영 못 보낼 행은 처음부터 적지 않는다. */
    @Test
    void 표에_등록되지_않은_종류는_적지_않는다() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                writer.append(new ItemForgotten(itemId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ITEM_FORGOTTEN");
        assertThat(rowsOf(itemId)).isZero();
    }

    @Test
    void aggregateId_가_없으면_적지_않는다() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                writer.append(new ItemSettled(null, "OK", 1L))))
                .isInstanceOf(NullPointerException.class);
    }

    private ItemSettled settled() {
        return new ItemSettled(itemId, "OK", 3L);
    }

    private int rowsOf(UUID aggregateId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM it_outbox_events WHERE aggregate_id = ?",
                Integer.class, (Object) UuidBinary.toBytes(aggregateId));
    }

    private int businessRows(String name) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM it_business_records WHERE name = ?",
                Integer.class, name);
    }

    /** 커밋 뒤에 받은 기록 신호. 발행기와 같은 단계(AFTER_COMMIT)에서 듣는다. */
    static class CommittedEvents {

        final List<OutboxAppended> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(OutboxAppended appended) {
            received.add(appended);
        }
    }
}
