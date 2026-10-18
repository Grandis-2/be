package com.grandis.nova.catalog.ranking;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 인기 검색어 집계(Redis). 같은 사람이 같은 검색어(합침 키)를 하루(KST)에 여러 번 검색해도 한 번만 센다. 시간(UTC 정시)마다 검색어별 수를 두고,
 * 순위는 지금 시간을 포함한 시간 표 {@link #TABLES}개를 합친다 — 지금 시간 표가 덜 찼으므로 덮는 범위는 24~25시간이다(10:30 이면 전날 10:00 부터).
 * 최근 24시간을 빠짐없이 덮도록 한 시간 길게 잡았다.
 *
 * 키: 시간별 수 {@code catalog:search:hour:{yyyyMMddHH}}(정렬 집합, 멤버 = 합침 키) · 그날 그 검색어를 센 사람
 * {@code catalog:search:seen:{KST yyyyMMdd}:{합침 키}}(집합) · 그 시간의 화면 표기 {@code catalog:search:display:{yyyyMMddHH}}(해시, 합침 키 → 그 시간에
 * 마지막으로 센 표기). 표기를 시간 표와 같이 두고 같이 만료시켜, 한 해시가 지금까지의 모든 검색어로 커지지 않게 한다. 순위의 표기는 가장 최근 시간의 것이다.
 */
@Component
public class SearchTermCounter {

    static final int WINDOW_HOURS = 24;
    /** 합치는 시간 표 수 — 지금 시간 표가 덜 찼으므로 24시간을 다 덮으려면 하나 더. */
    static final int TABLES = WINDOW_HOURS + 1;
    static final String HOUR_PREFIX = "catalog:search:hour:";
    static final String SEEN_PREFIX = "catalog:search:seen:";
    static final String DISPLAY_PREFIX = "catalog:search:display:";
    static final String UNION_KEY = "catalog:search:union";
    /** 시간 표 · 표기 해시는 합치는 범위(최대 25시간)보다 한 시간 더 둔다. */
    static final Duration HOUR_TTL = Duration.ofHours(TABLES + 1);
    static final Duration SEEN_TTL = Duration.ofDays(2);

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** 반환: 처음 센 사람이면 1, 아니면 0. 센 때만 표기를 이번 표기로 바꾼다. */
    private static final RedisScript<Long> RECORD = new DefaultRedisScript<>("""
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            if added == 1 then
              redis.call('ZINCRBY', KEYS[2], 1, ARGV[2])
              redis.call('HSET', KEYS[3], ARGV[2], ARGV[3])
              redis.call('PEXPIRE', KEYS[2], ARGV[5])
              redis.call('PEXPIRE', KEYS[3], ARGV[5])
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return added
            """, Long.class);

    /**
     * 시간 표들을 합쳐 많은 순 앞 ARGV[1]개의 표기. KEYS = {합칠 자리, 시간 표 N개(최근 먼저), 표기 해시 N개(같은 순서)}. 표기는 가장 최근 시간의 것.
     * 같은 수면 합침 키의 역순(ZREVRANGE).
     */
    private static final RedisScript<List> TOP = new DefaultRedisScript<>("""
            local n = (#KEYS - 1) / 2
            local hours = {}
            for i = 1, n do hours[i] = KEYS[1 + i] end
            redis.call('ZUNIONSTORE', KEYS[1], n, unpack(hours))
            local top = redis.call('ZREVRANGE', KEYS[1], 0, tonumber(ARGV[1]) - 1)
            redis.call('DEL', KEYS[1])
            local out = {}
            for _, key in ipairs(top) do
              local display = key
              for i = 1, n do
                local found = redis.call('HGET', KEYS[1 + n + i], key)
                if found then display = found break end
              end
              out[#out + 1] = display
            end
            return out
            """, List.class);

    private final StringRedisTemplate redis;

    public SearchTermCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return 이번에 셌으면 true(그 사람이 그날 처음 낸 검색어), 이미 셌으면 false */
    public boolean record(SearchTerm term, ProductViewer searcher, Instant now) {
        String day = LocalDate.ofInstant(now, ProductViewCounter.KOREA).format(DAY);
        String hour = HOUR.format(now);
        Long added = redis.execute(RECORD, List.of(SEEN_PREFIX + day + ":" + term.key(), HOUR_PREFIX + hour, DISPLAY_PREFIX + hour),
                searcher.key(), term.key(), term.display(), String.valueOf(SEEN_TTL.toMillis()), String.valueOf(HOUR_TTL.toMillis()));
        return added != null && added == 1;
    }

    /** 최근 24시간(시간 표 {@link #TABLES}개) 검색 수 순으로 앞 limit 개의 화면 표기. */
    public List<String> top(Instant now, int limit) {
        List<String> hours = new ArrayList<>();
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        for (int i = 0; i < TABLES; i++) {
            hours.add(HOUR.format(hour.minus(Duration.ofHours(i))));
        }
        List<String> keys = new ArrayList<>();
        keys.add(UNION_KEY);
        hours.forEach(h -> keys.add(HOUR_PREFIX + h));
        hours.forEach(h -> keys.add(DISPLAY_PREFIX + h));
        List<?> displays = redis.execute(TOP, keys, String.valueOf(limit));
        return displays == null ? List.of() : displays.stream().map(Object::toString).toList();
    }
}
