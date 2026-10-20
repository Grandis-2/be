package com.grandis.nova.catalog.ranking;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 상품 조회수(Redis). 같은 사람이 같은 상품을 하루(KST)에 여러 번 봐도 한 번만 센다. 날마다 상품별 조회수 표(정렬 집합)를 두고, 랭킹은 오늘을 포함한
 * 최근 {@link #WINDOW_DAYS}일(KST 날짜)을 합친다.
 *
 * 키: 그날 조회수 {@code catalog:views:day:{yyyyMMdd}} (정렬 집합, 멤버 = 상품 id) · 그날 그 상품을 본 사람 {@code catalog:views:seen:{yyyyMMdd}:{상품 id}}
 * (집합, 멤버 = "c:회원 id" 또는 "a:익명 id"). 둘 다 스크립트 한 번으로 쓴다 — 본 사람에 넣은 쪽만 조회수를 올린다.
 */
@Component
public class ProductViewCounter {

    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 랭킹이 합치는 날 수 — 오늘과 그 앞 이틀. */
    static final int WINDOW_DAYS = 3;
    static final String DAY_PREFIX = "catalog:views:day:";
    static final String SEEN_PREFIX = "catalog:views:seen:";
    static final String UNION_KEY = "catalog:views:union";
    /** 그날 조회수 표는 합치는 날 수보다 하루 더 둔다. */
    static final Duration DAY_TTL = Duration.ofDays(WINDOW_DAYS + 1);
    /** 본 사람 집합은 그날만 쓴다 — 마지막으로 쓴 때부터 이틀(날이 바뀐 뒤에도 하루 넘게 남는다). */
    static final Duration SEEN_TTL = Duration.ofDays(2);

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 반환: 이번에 센 수(처음 본 사람이면 1, 아니면 0). */
    private static final RedisScript<Long> RECORD = new DefaultRedisScript<>("""
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            if added == 1 then
              redis.call('ZINCRBY', KEYS[2], 1, ARGV[2])
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            return added
            """, Long.class);

    /** 날마다의 표를 합쳐 조회수가 많은 순으로 ARGV[1] 번째부터 ARGV[2]개. 같은 수면 상품 id 의 역순(ZREVRANGE). 합친 표는 그 자리에서 지운다. */
    private static final RedisScript<List> TOP = new DefaultRedisScript<>("""
            local days = {}
            for i = 2, #KEYS do days[#days + 1] = KEYS[i] end
            redis.call('ZUNIONSTORE', KEYS[1], #days, unpack(days))
            local from = tonumber(ARGV[1])
            local top = redis.call('ZREVRANGE', KEYS[1], from, from + tonumber(ARGV[2]) - 1)
            redis.call('DEL', KEYS[1])
            return top
            """, List.class);

    private final StringRedisTemplate redis;

    public ProductViewCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 그 사람의 그날(now 의 KST 날짜) 조회를 센다.
     *
     * @param viewer "c:회원 id" 또는 "a:익명 id"({@link ProductViewer#key})
     * @return 이번에 셌으면 true(그날 처음), 이미 셌으면 false
     */
    public boolean record(UUID productId, ProductViewer viewer, Instant now) {
        String day = day(now, 0);
        Long added = redis.execute(RECORD, List.of(SEEN_PREFIX + day + ":" + productId, DAY_PREFIX + day),
                viewer.key(), productId.toString(), String.valueOf(SEEN_TTL.toMillis()), String.valueOf(DAY_TTL.toMillis()));
        return added != null && added == 1;
    }

    /** 최근 {@link #WINDOW_DAYS}일 조회수 순으로 앞 limit 개의 상품 id. */
    public List<UUID> top(Instant now, int limit) {
        return top(now, 0, limit);
    }

    /** 최근 {@link #WINDOW_DAYS}일 조회수 순으로 offset 번째(0 부터)부터 limit 개의 상품 id. 부를 때마다 날마다의 표를 다시 합친다. */
    public List<UUID> top(Instant now, int offset, int limit) {
        List<String> keys = new ArrayList<>();
        keys.add(UNION_KEY);
        for (int i = 0; i < WINDOW_DAYS; i++) {
            keys.add(DAY_PREFIX + day(now, i));
        }
        List<?> ids = redis.execute(TOP, keys, String.valueOf(offset), String.valueOf(limit));
        return ids == null ? List.of() : ids.stream().map(id -> UUID.fromString(id.toString())).toList();
    }

    private static String day(Instant now, int daysAgo) {
        return LocalDate.ofInstant(now, KOREA).minusDays(daysAgo).format(DAY);
    }
}
