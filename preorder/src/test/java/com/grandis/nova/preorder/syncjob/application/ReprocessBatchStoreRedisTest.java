package com.grandis.nova.preorder.syncjob.application;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.syncjob.application.ReprocessBatchStore.StoredBatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 Redis 에 기록해 본다. 시험마다 새 batchId 를 키로 쓴다. */
@PreorderIntegrationTest
class ReprocessBatchStoreRedisTest {

    @Autowired
    StringRedisTemplate redis;

    final Instant now = Instant.parse("2026-10-25T01:00:00Z");

    @Test
    void 시작부터_보관_시간이_걸리고_요청을_남긴_작업만_보낸_것으로_둔다() {
        ReprocessBatchStore store = store(Duration.ofHours(24));
        UUID batchId = UUID.randomUUID();
        UUID sent = UUID.randomUUID();
        UUID unsent = UUID.randomUUID();

        store.start(batchId, List.of(sent, unsent), 1, now);
        assertThat(redis.getExpire(ReprocessBatchStore.key(batchId), TimeUnit.MILLISECONDS)).isPositive();
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

    @Test
    void 키가_없으면_진행과_끝을_기록하지_않아_반쪽_해시를_만들지_않는다() {
        ReprocessBatchStore store = store(Duration.ofHours(24));
        UUID batchId = UUID.randomUUID();

        store.progress(batchId, UUID.randomUUID(), now);
        store.finish(batchId, now);

        assertThat(redis.hasKey(ReprocessBatchStore.key(batchId))).isFalse();
        assertThat(store.find(batchId)).isEmpty();
    }

    private ReprocessBatchStore store(Duration ttl) {
        return new ReprocessBatchStore(redis, new ReprocessBatchProperties(ttl));
    }
}
