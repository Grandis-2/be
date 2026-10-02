package com.grandis.nova.catalog.outbox.publish;

import com.grandis.nova.catalog.outbox.OutboxEvent;
import com.grandis.nova.catalog.outbox.OutboxEventRepository;
import com.grandis.nova.catalog.outbox.OutboxWriter;
import com.grandis.nova.catalog.registration.InStockProductRegistered;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 로그 전송(nova.outbox.transport=log) 위에서 발행 흐름 — 커밋 직후 발행과 릴레이. 큐까지 가는 것은 OutboxSqsFlowTest 가 본다. */
@CatalogIntegrationTest
class OutboxPublishTest {

    @Autowired OutboxWriter writer;
    @Autowired OutboxRelay relay;
    @Autowired OutboxEventRepository outboxEvents;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("커밋 직후 발행기가 보내고 발행 완료로 표시한다 — 릴레이를 기다리지 않는다")
    void publishesRightAfterCommit() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                writer.append(new InStockProductRegistered(-201L, List.of(new InStockProductRegistered.Item(1L, 1)))));

        assertThat(waitUntilPublished(-201L)).as("발행 실행기가 커밋 뒤 따로 보낸다").isTrue();
    }

    @Test
    @DisplayName("릴레이는 오래된 미발행 행만 보낸다 — 방금 만든 행 · 리스가 살아 있는 행은 건너뛰고, 리스가 끝난 행(가져간 쪽이 죽음)은 다시 가져간다")
    void relaySendsOnlyOldUnclaimedRows() {
        insertUnpublished(-211L, "UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE", null, 0);
        insertUnpublished(-212L, "UTC_TIMESTAMP(6)", null, 0);                                              // relay-after(1m) 전
        insertUnpublished(-213L, "UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE", "UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE", 0); // 남의 리스
        insertUnpublished(-214L, "UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE", "UTC_TIMESTAMP(6) - INTERVAL 1 SECOND", 0); // 끝난 리스

        relay.relay();

        assertThat(publishedAt(-211L)).as("오래된 미발행 행").isNotNull();
        assertThat(publishedAt(-212L)).as("방금 만든 행은 커밋 직후 발행의 몫").isNull();
        assertThat(publishedAt(-213L)).as("리스가 살아 있는 행").isNull();
        assertThat(publishedAt(-214L)).as("리스가 끝난 행 — 이것이 없으면 보내다 죽은 행이 영원히 남는다").isNotNull();
    }

    @Test
    @DisplayName("리스 연장은 자기 리스 값일 때만 된다 — 그사이 다른 인스턴스가 가져갔으면 0 이라 보내지 않는다")
    void renewLeaseRequiresOwnLease() {
        insertUnpublished(-231L, "UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE", "'2099-01-01 00:00:00.123456'", 0);
        Long id = idOf(-231L);
        Instant mine = Instant.parse("2099-01-01T00:00:00.123456Z");
        Instant renewed = mine.plusSeconds(60);

        assertThat(outboxEvents.renewLease(id, mine.plusNanos(1_000), renewed)).as("남의 리스 값").isZero();
        assertThat(outboxEvents.renewLease(id, mine, renewed)).as("자기 리스 값").isEqualTo(1);
        assertThat(outboxEvents.renewLease(id, mine, renewed.plusSeconds(60))).as("한 번 연장하면 옛 값은 더 이상 내 리스가 아니다").isZero();
    }

    @Test
    @DisplayName("릴레이는 실패가 적은 행부터 가져간다 — 늘 실패하는 먼저 만든 행이 뒤 행을 막지 않는다")
    void claimsFewerFailuresFirst() {
        insertUnpublished(-241L, "UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE", null, 9);   // 먼저 만들었고(id 작음) 9번 실패
        insertUnpublished(-242L, "UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE", null, 0);

        List<Long> order = new TransactionTemplate(transactionManager).execute(status ->
                outboxEvents.lockClaimable(Instant.now(), Instant.now(), 1_000).stream()
                        .map(OutboxEvent::getAggregateId).filter(id -> id == -241L || id == -242L).toList());

        assertThat(order).containsExactly(-242L, -241L);
        jdbcTemplate.update("UPDATE catalog_outbox_events SET published_at = UTC_TIMESTAMP(6) WHERE aggregate_id IN (-241, -242)");
    }

    @Test
    @DisplayName("미발행 현황 — 미발행 행 수와 최다 실패 횟수를 센다(릴레이가 주기마다 경고 로그로 낸다)")
    void countsBacklog() {
        long before = outboxEvents.countUnpublished();
        insertUnpublished(-251L, "UTC_TIMESTAMP(6)", null, 0);
        insertUnpublished(-252L, "UTC_TIMESTAMP(6)", null, 4_321);

        assertThat(outboxEvents.countUnpublished()).isEqualTo(before + 2);
        assertThat(outboxEvents.maxUnpublishedAttempts()).isEqualTo(4_321);

        jdbcTemplate.update("UPDATE catalog_outbox_events SET published_at = UTC_TIMESTAMP(6) WHERE aggregate_id IN (-251, -252)");
        assertThat(outboxEvents.countUnpublished()).as("발행된 행은 세지 않는다").isEqualTo(before);
    }

    private void insertUnpublished(Long aggregateId, String createdAt, String leaseUntil, int attempts) {
        jdbcTemplate.update("""
                INSERT INTO catalog_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, publish_attempts,
                                                   lease_until, created_at)
                VALUES (?, 'PRODUCT', ?, 'IN_STOCK_PRODUCT_REGISTERED', JSON_OBJECT('items', JSON_ARRAY()), ?, %s, %s)
                """.formatted(leaseUntil == null ? "NULL" : leaseUntil, createdAt), UUID.randomUUID().toString(), aggregateId, attempts);
    }

    private Long idOf(Long aggregateId) {
        return jdbcTemplate.queryForObject("SELECT id FROM catalog_outbox_events WHERE aggregate_id = ?", Long.class, aggregateId);
    }

    private Object publishedAt(Long aggregateId) {
        return jdbcTemplate.queryForObject("SELECT published_at FROM catalog_outbox_events WHERE aggregate_id = ?", Object.class, aggregateId);
    }

    private boolean waitUntilPublished(Long aggregateId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (publishedAt(aggregateId) != null) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
