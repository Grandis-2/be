package com.grandis.nova.preorder.query.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 예약 수 기록을 범위(전체 · 상품)마다 Redis 정렬 집합 하나에 시각을 점수로 둔다. 보관 시간이 지난 기록은 쓸 때 지우고,
 * 더 기록하지 않는 상품의 키는 보관 시간 뒤 사라진다.
 */
@Component
class PreorderStatsHistoryStore {

    static final String KEY_PREFIX = "preorder:stats:history:";
    private static final String ALL = "all";
    private static final String TICK = "tick:";

    /** ARGV[1] 시각(밀리초), ARGV[2] 기록, ARGV[3] 보관 밀리초. */
    private static final RedisScript<Long> APPEND = new DefaultRedisScript<>("""
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. (tonumber(ARGV[1]) - tonumber(ARGV[3])))
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1""", Long.class);

    private static final Logger log = LoggerFactory.getLogger(PreorderStatsHistoryStore.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Duration interval;
    private final Duration retention;

    PreorderStatsHistoryStore(StringRedisTemplate redis, JsonMapper jsonMapper,
                              PreorderStatsHistoryProperties properties) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.interval = properties.interval();
        this.retention = properties.retention();
    }

    /** 그 시각이 든 주기를 이 태스크가 맡았다 — 태스크가 여럿이어도 주기마다 한 곳만 기록한다. */
    boolean claim(Instant at) {
        long step = at.toEpochMilli() / interval.toMillis();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY_PREFIX + TICK + step, "1",
                interval.multipliedBy(2)));
    }

    /** @param productId null 이면 전체 */
    void append(UUID productId, StatsSample sample) {
        redis.execute(APPEND, List.of(key(productId)), String.valueOf(sample.at().toEpochMilli()),
                jsonMapper.writeValueAsString(sample), String.valueOf(retention.toMillis()));
    }

    /**
     * from 이후 기록, 시각 순.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — Redis 가 응답하지 않는다
     */
    List<StatsSample> since(UUID productId, Instant from) {
        Set<String> found;
        try {
            found = redis.opsForZSet().rangeByScore(key(productId), from.toEpochMilli(), Double.POSITIVE_INFINITY);
        } catch (DataAccessException e) {
            log.warn("예약 수 기록을 읽지 못했다 productId={}", productId, e);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (found == null) {
            return List.of();
        }
        return found.stream().map(this::parse).filter(Objects::nonNull).toList();
    }

    static String key(UUID productId) {
        return KEY_PREFIX + (productId == null ? ALL : productId);
    }

    private StatsSample parse(String member) {
        try {
            return jsonMapper.readValue(member, StatsSample.class);
        } catch (JacksonException e) {
            log.warn("읽을 수 없는 예약 수 기록을 건너뛴다: {}", e.toString());
            return null;
        }
    }
}
