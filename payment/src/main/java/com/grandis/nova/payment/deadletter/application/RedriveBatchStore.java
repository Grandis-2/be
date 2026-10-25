package com.grandis.nova.payment.deadletter.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 일괄 되돌리기 진행을 Redis 해시 하나에 둔다(preorder ReprocessBatchStore 와 한 벌). 보내는 인스턴스와 묻는 인스턴스가 달라도 같은 값을 본다.
 * 기록은 스크립트 하나로 보관 시간까지 함께 걸고, 진행 · 끝 기록은 키가 있을 때만 해 반쪽 해시를 만들지 않는다.
 * 진행 기록이 실패해도 되돌리기는 멈추지 않는다 — 진행 조회만 늦거나 빠진다.
 */
@Component
class RedriveBatchStore {

    static final String KEY_PREFIX = "payment:redrive-batch:";

    private static final String TARGETS = "targets";
    private static final String SKIPPED = "skipped";
    private static final String STARTED_AT = "startedAt";
    private static final String ATTEMPTED = "attempted";
    private static final String LAST_PROGRESS_AT = "lastProgressAt";
    private static final String FINISHED_AT = "finishedAt";
    /** 큐로 보낸 행마다 이 접두어 + 행 id 칸을 둔다. */
    private static final String SENT_PREFIX = "sent:";

    /** ARGV[1] 보관 밀리초, 나머지는 칸 · 값 쌍. */
    private static final RedisScript<Long> START = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], unpack(ARGV, 2))
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1""", Long.class);

    /** ARGV[1] 보관 밀리초, ARGV[2] 시각, ARGV[3] 큐로 보낸 행 id(못 보냈으면 빈 문자열). */
    private static final RedisScript<Long> PROGRESS = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('HINCRBY', KEYS[1], 'attempted', 1)
            redis.call('HSET', KEYS[1], 'lastProgressAt', ARGV[2])
            if ARGV[3] ~= '' then redis.call('HSET', KEYS[1], 'sent:' .. ARGV[3], '1') end
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1""", Long.class);

    /** ARGV[1] 보관 밀리초, ARGV[2] 시각. */
    private static final RedisScript<Long> FINISH = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[1], 'finishedAt', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1""", Long.class);

    private static final Logger log = LoggerFactory.getLogger(RedriveBatchStore.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    RedriveBatchStore(StringRedisTemplate redis, RedriveBatchProperties properties) {
        this.redis = redis;
        this.ttl = properties.progressTtl();
    }

    /** 대상이 없으면 시작과 함께 끝난 것으로 둔다. */
    void start(UUID batchId, List<UUID> targets, int skipped, Instant startedAt) {
        List<String> args = new ArrayList<>(List.of(ttlMillis(),
                TARGETS, targets.stream().map(UUID::toString).collect(Collectors.joining(",")),
                SKIPPED, String.valueOf(skipped),
                STARTED_AT, startedAt.toString(),
                ATTEMPTED, "0",
                LAST_PROGRESS_AT, startedAt.toString()));
        if (targets.isEmpty()) {
            args.addAll(List.of(FINISHED_AT, startedAt.toString()));
        }
        try {
            redis.execute(START, List.of(key(batchId)), args.toArray());
        } catch (DataAccessException e) {
            log.warn("일괄 되돌리기 진행을 남기지 못했다 — 되돌리기는 이어간다 batchId={}", batchId, e);
        }
    }

    /** 대상 하나를 다뤘다. sentId 는 큐로 보낸 행, 그 사이 상태가 바뀌었거나 보내기 결과를 남기지 못했으면 null. */
    void progress(UUID batchId, UUID sentId, Instant at) {
        try {
            redis.execute(PROGRESS, List.of(key(batchId)), ttlMillis(), at.toString(),
                    sentId == null ? "" : sentId.toString());
        } catch (DataAccessException e) {
            log.warn("일괄 되돌리기 진행을 남기지 못했다 batchId={}", batchId, e);
        }
    }

    void finish(UUID batchId, Instant at) {
        try {
            redis.execute(FINISH, List.of(key(batchId)), ttlMillis(), at.toString());
        } catch (DataAccessException e) {
            log.warn("일괄 되돌리기 끝을 남기지 못했다 batchId={}", batchId, e);
        }
    }

    /**
     * 없거나 보관 시간이 지났으면 비어 있다.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — Redis 가 응답하지 않는다
     */
    Optional<StoredBatch> find(UUID batchId) {
        Map<Object, Object> fields;
        try {
            fields = redis.opsForHash().entries(key(batchId));
        } catch (DataAccessException e) {
            log.warn("일괄 되돌리기 진행을 읽지 못했다 batchId={}", batchId, e);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        String targets = (String) fields.get(TARGETS);
        Set<UUID> sent = fields.keySet().stream()
                .map(String.class::cast)
                .filter(field -> field.startsWith(SENT_PREFIX))
                .map(field -> UUID.fromString(field.substring(SENT_PREFIX.length())))
                .collect(Collectors.toSet());
        String finishedAt = (String) fields.get(FINISHED_AT);
        return Optional.of(new StoredBatch(
                targets.isEmpty() ? List.of() : Arrays.stream(targets.split(",")).map(UUID::fromString).toList(),
                Integer.parseInt((String) fields.get(SKIPPED)), Instant.parse((String) fields.get(STARTED_AT)),
                Integer.parseInt((String) fields.get(ATTEMPTED)), sent,
                Instant.parse((String) fields.get(LAST_PROGRESS_AT)),
                finishedAt == null ? null : Instant.parse(finishedAt)));
    }

    static String key(UUID batchId) {
        return KEY_PREFIX + batchId;
    }

    private String ttlMillis() {
        return String.valueOf(ttl.toMillis());
    }

    /**
     * @param targets   되돌릴 행 id(보내는 순서)
     * @param attempted 앞에서부터 다룬 대상 수
     * @param sent      그중 큐로 보낸 행
     */
    record StoredBatch(List<UUID> targets, int skipped, Instant startedAt, int attempted, Set<UUID> sent,
                       Instant lastProgressAt, Instant finishedAt) {
    }
}
