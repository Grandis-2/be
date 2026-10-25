package com.grandis.nova.payment.deadletter.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.payment.PaymentErrorCode;
import com.grandis.nova.payment.deadletter.application.RedriveBatchStore.StoredBatch;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.support.RedisContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 실제 Redis 에 기록해 본다(preorder ReprocessBatchStoreRedisTest 와 한 벌). 시험마다 새 batchId 를 키로 쓴다. */
@PaymentIntegrationTest
@Import(RedisContainerConfig.class)
class RedriveBatchStoreRedisTest {

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    DeadLetterAdminService deadLetterAdminService;

    final Instant now = Instant.parse("2026-10-25T01:00:00Z");

    @Test
    void 시작부터_보관_시간이_걸리고_큐로_보낸_행만_보낸_것으로_둔다() {
        Duration ttl = Duration.ofHours(24);
        RedriveBatchStore store = store(ttl);
        UUID batchId = UUID.randomUUID();
        UUID sent = UUID.randomUUID();
        UUID unsent = UUID.randomUUID();

        store.start(batchId, List.of(sent, unsent), 1, now);
        assertThat(ttlMillis(batchId)).isPositive().isLessThanOrEqualTo(ttl.toMillis());
        store.progress(batchId, sent, now.plusSeconds(1));
        store.progress(batchId, null, now.plusSeconds(2));
        store.finish(batchId, now.plusSeconds(3));

        StoredBatch stored = store.find(batchId).orElseThrow();
        assertThat(stored.targets()).containsExactly(sent, unsent);
        assertThat(stored.skipped()).isEqualTo(1);
        assertThat(stored.attempted()).isEqualTo(2);
        assertThat(stored.sent()).isEqualTo(Set.of(sent));
        assertThat(stored.lastProgressAt()).isEqualTo(now.plusSeconds(2));
        assertThat(stored.finishedAt()).isEqualTo(now.plusSeconds(3));
    }

    // 진행 · 끝 기록마다 보관 시간을 다시 건다 — 오래 걸리는 일괄도 마지막 기록부터 하루 남는다
    @Test
    void 진행과_끝을_기록할_때마다_보관_시간을_다시_건다() {
        Duration ttl = Duration.ofHours(24);
        RedriveBatchStore store = store(ttl);
        UUID batchId = UUID.randomUUID();
        store.start(batchId, List.of(UUID.randomUUID()), 0, now);

        redis.expire(RedriveBatchStore.key(batchId), Duration.ofSeconds(60));
        store.progress(batchId, null, now.plusSeconds(1));
        assertThat(ttlMillis(batchId)).isGreaterThan(Duration.ofSeconds(60).toMillis())
                .isLessThanOrEqualTo(ttl.toMillis());

        redis.expire(RedriveBatchStore.key(batchId), Duration.ofSeconds(60));
        store.finish(batchId, now.plusSeconds(2));
        assertThat(ttlMillis(batchId)).isGreaterThan(Duration.ofSeconds(60).toMillis())
                .isLessThanOrEqualTo(ttl.toMillis());
    }

    @Test
    void 키가_없으면_진행과_끝을_기록하지_않아_반쪽_해시를_만들지_않는다() {
        RedriveBatchStore store = store(Duration.ofHours(24));
        UUID batchId = UUID.randomUUID();

        store.progress(batchId, UUID.randomUUID(), now);
        store.finish(batchId, now);

        assertThat(redis.hasKey(RedriveBatchStore.key(batchId))).isFalse();
        assertThat(store.find(batchId)).isEmpty();
    }

    @Test
    void 보관_시간이_지나면_비어_있다() {
        RedriveBatchStore store = store(Duration.ofMillis(300));
        UUID batchId = UUID.randomUUID();

        store.start(batchId, List.of(UUID.randomUUID()), 0, now);

        await().atMost(Duration.ofSeconds(5)).until(() -> store.find(batchId).isEmpty());
    }

    @Test
    void 키가_사라진_일괄의_진행은_404_다() {
        UUID batchId = deadLetterAdminService.redriveBatch(List.of(UUID.randomUUID()), null, null, 200, "admin")
                .batchId();
        assertThat(deadLetterAdminService.findBatch(batchId).finishedAt()).isNotNull();

        redis.delete(RedriveBatchStore.key(batchId));

        assertThatThrownBy(() -> deadLetterAdminService.findBatch(batchId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.REDRIVE_BATCH_NOT_FOUND));
    }

    private Long ttlMillis(UUID batchId) {
        return redis.getExpire(RedriveBatchStore.key(batchId), TimeUnit.MILLISECONDS);
    }

    private RedriveBatchStore store(Duration ttl) {
        return new RedriveBatchStore(redis, new RedriveBatchProperties(ttl));
    }
}
