package com.grandis.nova.preorder.syncjob.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 일괄 재처리 진행을 Redis 해시 하나에 둔다. 보내는 인스턴스와 묻는 인스턴스가 달라도 같은 값을 본다.
 * 진행 기록이 실패해도 재처리는 멈추지 않는다 — 진행 조회만 늦거나 빠진다.
 * 기록할 때마다 보관 시간을 다시 걸어, 만료된 키를 증가가 보관 시간 없이 반쪽으로 되살리지 않게 한다.
 */
@Component
class ReprocessBatchStore {

    static final String KEY_PREFIX = "preorder:reprocess-batch:";

    private static final String TARGETS = "targets";
    private static final String SKIPPED = "skipped";
    private static final String STARTED_AT = "startedAt";
    private static final String ATTEMPTED = "attempted";
    private static final String SENT = "sent";
    private static final String LAST_PROGRESS_AT = "lastProgressAt";
    private static final String FINISHED_AT = "finishedAt";

    private static final Logger log = LoggerFactory.getLogger(ReprocessBatchStore.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    ReprocessBatchStore(StringRedisTemplate redis, ReprocessBatchProperties properties) {
        this.redis = redis;
        this.ttl = properties.progressTtl();
    }

    /** 대상이 없으면 시작과 함께 끝난 것으로 둔다. */
    void start(UUID batchId, List<UUID> targets, int skipped, Instant startedAt) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(TARGETS, targets.stream().map(UUID::toString).collect(Collectors.joining(",")));
        fields.put(SKIPPED, String.valueOf(skipped));
        fields.put(STARTED_AT, startedAt.toString());
        fields.put(ATTEMPTED, "0");
        fields.put(SENT, "0");
        fields.put(LAST_PROGRESS_AT, startedAt.toString());
        if (targets.isEmpty()) {
            fields.put(FINISHED_AT, startedAt.toString());
        }
        try {
            hash().putAll(key(batchId), fields);
            redis.expire(key(batchId), ttl);
        } catch (DataAccessException e) {
            log.warn("일괄 재처리 진행을 남기지 못했다 — 재처리는 이어간다 batchId={}: {}", batchId, e.toString());
        }
    }

    /** 대상 하나를 다뤘다. sent 면 재처리 요청을 남겼고, 아니면 그 사이 상태가 바뀌었거나 남기지 못했다. */
    void progress(UUID batchId, boolean sent, Instant at) {
        try {
            hash().increment(key(batchId), ATTEMPTED, 1);
            if (sent) {
                hash().increment(key(batchId), SENT, 1);
            }
            hash().put(key(batchId), LAST_PROGRESS_AT, at.toString());
            redis.expire(key(batchId), ttl);
        } catch (DataAccessException e) {
            log.warn("일괄 재처리 진행을 남기지 못했다 batchId={}: {}", batchId, e.toString());
        }
    }

    void finish(UUID batchId, Instant at) {
        try {
            hash().put(key(batchId), FINISHED_AT, at.toString());
            redis.expire(key(batchId), ttl);
        } catch (DataAccessException e) {
            log.warn("일괄 재처리 끝을 남기지 못했다 batchId={}: {}", batchId, e.toString());
        }
    }

    /**
     * 없거나 보관 시간이 지났으면 비어 있다.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — Redis 가 응답하지 않는다
     */
    Optional<StoredBatch> find(UUID batchId) {
        Map<String, String> fields;
        try {
            fields = hash().entries(key(batchId));
        } catch (DataAccessException e) {
            log.warn("일괄 재처리 진행을 읽지 못했다 batchId={}: {}", batchId, e.toString());
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        String targets = fields.get(TARGETS);
        return Optional.of(new StoredBatch(
                targets.isEmpty() ? List.of() : Arrays.stream(targets.split(",")).map(UUID::fromString).toList(),
                Integer.parseInt(fields.get(SKIPPED)), Instant.parse(fields.get(STARTED_AT)),
                Integer.parseInt(fields.get(ATTEMPTED)), Integer.parseInt(fields.get(SENT)),
                Instant.parse(fields.get(LAST_PROGRESS_AT)),
                fields.containsKey(FINISHED_AT) ? Instant.parse(fields.get(FINISHED_AT)) : null));
    }

    static String key(UUID batchId) {
        return KEY_PREFIX + batchId;
    }

    private HashOperations<String, String, String> hash() {
        return redis.opsForHash();
    }

    /**
     * @param targets   보낼 작업 id(보내는 순서)
     * @param attempted 앞에서부터 다룬 대상 수
     * @param sent      그중 재처리 요청을 남긴 수
     */
    record StoredBatch(List<UUID> targets, int skipped, Instant startedAt, int attempted, int sent,
                       Instant lastProgressAt, Instant finishedAt) {
    }
}
