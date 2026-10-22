package com.grandis.nova.common.redis.cache;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.redis.cache.ReadCacheEvents.Outcome;
import com.grandis.nova.common.redis.cache.ReadCacheEvents.RefreshResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 공개 조회 결과를 Redis 에 잠깐 들고 있다가 같은 요청에 돌려주는 읽기 캐시. 정본은 늘 DB 다 — 캐시는 DB 를 덜 읽으려고 둔다.
 *
 * <ul>
 *   <li><b>장애</b>: Redis 읽기 · 쓰기가 실패하면 캐시 없이 loader(DB)로 답한다. 실패가 이어지면 차단기가 열려 {@link #OPEN_FOR} 동안
 *       Redis 를 부르지 않는다 — 요청마다 Redis 제한 시간만큼 기다리지 않게. 그 뒤 몇 번 불러 보고 되면 다시 쓴다. 세대나 값을 못 읽으면 loader 는
 *       우회({@link LoadPath#BYPASS})를 받고 그 결과는 넣지 않는다. 값 키가 다른 타입으로 오염되면(키는 접두사 · 지문 · 세대라 이 클래스만 쓴다)
 *       세대가 오르거나 그 키가 만료될 때까지(밖에서 쓴 키는 TTL 이 없어 세대가 오를 때까지) 우회하고, 그때마다 차단기에 실패로 센다 — 차단기는
 *       인스턴스에 하나라 모든 캐시가 같이 쓰므로, 인기 키가 오염되면 차단기가 열려 그 인스턴스의 캐시 전체가 {@link #OPEN_FOR} 마다 우회할 수 있다.</li>
 *   <li><b>몰림</b>: 같은 키(세대 번호 포함)를 동시에 찾는 요청은 이 인스턴스 안에서 loader 를 한 번만 부르고 나머지는 그 결과를 받는다 —
 *       인기 키가 비는 순간(만료 · Redis 복구 직후) DB 로 한꺼번에 가지 않게. loader 의 예외(404 등)도 같이 받는다. 같이 받는 쪽은
 *       {@link #WAIT_FOR_LOAD} 까지만 기다린다(넘으면 503). 자리를 잡은 요청은 원천을 부르기 전에 값을 한 번 더 본다 — 값이 없음을 본 뒤 자리를
 *       잡기 전에 앞 조회가 넣고 끝냈으면 그 값을 받는다(안 보면 원천을 한 번 더 읽는다). 다시 보기를 못 하면(Redis 실패 · 차단기) 없음으로 보지 않고
 *       우회한다 — 그때는 이미 그 채우기 자리에 붙어 기다린 요청도 우회 결과를 받는다(넣지 않는다). 그 밖에는 우회는 우회끼리만 같이 받는다(세대를
 *       읽었으면 그 번호까지 같은 것끼리) — 같은 키를 채우기와 우회가 동시에 찾으면 loader 를 각각 한 번 부른다.</li>
 *   <li><b>장애 중 사본</b>: 사본을 켠 캐시({@link #spec(String, Class, Duration, Duration)})는 Redis 를 못 읽을 때 우회하기 전에 이 인스턴스의
 *       사본({@link OutageCopies})으로 답한다 — Redis 가 잠깐 늦어 차단기가 열린 동안 요청이 모두 원천으로 몰려 줄을 서지 않게.</li>
 *   <li><b>미리 갱신</b>: 읽은 지 TTL 의 {@link #REFRESH_AFTER_PERCENT}% 가 지난 값을 꺼낸 요청은 그 값을 바로 받고, 같은 키(세대 번호 포함)의
 *       다시 읽기를 갱신 실행기에 이 인스턴스에서 하나만 맡긴다 — 만료 순간 모든 요청이 한 건의 읽기를 기다리지 않게(부하 시험에서 목록 · 리뷰의 캐시
 *       대상 요청이 그 대기로 목록 0.3~1.4초 · 리뷰 0.2~2.2초 걸렸다). 요청이 없는 키는 갱신하지 않는다. 갱신은 몰림의 "한 번만 읽기" 자리를 같이 써서, 갱신이 끝나기 전에
 *       값이 만료되면 그 뒤 요청은 기다리는 쪽이 된다(상한 그대로). 갱신이 실패해도 들고 있던 값의 만료는 늘지 않는다. 실행기가 차면 맡기지 않고, 그 값이 만료될 때까지
 *       그 키는 다시 맡기지 않는다.
 *       갱신한 값은 읽기 시작할 때의 세대 키에 들어가, 그사이 세대가 올랐으면 새 세대 조회에는 안 보인다. 돌 차례가 왔을 때 Redis 의
 *       지금 세대가 맡길 때와 다르면(올랐거나, 세대 키가 지워져 다시 만들어졌거나, 복원으로 되돌아갔으면) 읽지 않는다. 돌 차례에 지금 세대나 값을 Redis 에서 못 읽으면(차단기 · 장애) 읽지 않는다 — 넣지 못할 값을 원천에 묻지 않게. 그 키는 막아 두지 않아 다음 히트가 다시
 *       맡긴다(한 번의 시간 초과로 만료까지 미리 갱신이 멈추지 않게 — 간헐 실패에서는 히트마다 키당 하나씩 다시 맡긴다). 원천을 읽은 뒤 못 넣으면
 *       (아래) 막는다 — 원천을 읽기 전에 Redis 를 못 쓰면 안 막고, 읽은 뒤 못 넣으면 막는다. 인스턴스마다 따로라 같은 키를 인스턴스 수만큼
 *       갱신할 수 있다(만료 때 인스턴스마다 한 번 읽던 것과 같다). 같은 세대 키에는 마지막에 넣은 쪽이 남는다(직접 읽기와 같다) — 세대가 덮지
 *       않는 데이터를 읽는 loader(배너가 읽는 상품 노출 · 홍보 기간)는 늦게 넣은 인스턴스의 값으로 되돌아갈 수 있고, 그 값의 나이도 읽은
 *       시각 + TTL 안이다. 큐에서 늦게 돈 갱신은 그사이 더 새 값이 들어왔으면 읽지 않는다. 갱신 결과를
 *       넣지 못했으면(넣지 않는 값 · 저장 상한 · 차단기 · 남은 시간 없음) 들고 있던 값이 만료될 때까지 그 키는 다시 맡기지 않는다(실패도 같다).
 *       갱신 실행기가 없으면 미리 갱신하지 않는다. loader 는 요청 맥락(SecurityContext · RequestContextHolder)이 없는 실행기 스레드에서도 돌 수
 *       있어야 한다 — 토큰을 릴레이하는 호출을 loader 에 넣으면 갱신이 401 로 실패하고 그 실패가 기다린 요청에 퍼진다.</li>
 *   <li><b>무효화</b>: 키에 세대 번호를 넣고, 바뀐 쪽이 {@link #bumpAfterCommit} 으로 번호를 올린다 — 조건마다 다른 키(목록 · 검색)를 하나씩
 *       찾아 지우지 않는다. 올리기는 트랜잭션 커밋 뒤라 롤백된 수정으로 비우지 않는다. 올리기가 실패하거나 차단기가 열려 있으면 미뤄 두었다가 이 인스턴스가
 *       Redis 를 다시 읽는 첫 조회에서 올린다 — 미룬 것은 메모리에만 있어 그 전에 인스턴스가 내려가면 사라지고, 그때 최악은 그 캐시의 TTL 이다.
 *       커밋 전에 DB 를 읽은 요청의 옛 값은 그때 정한 옛 세대 키에 들어가 새 세대 조회에는 안 보인다.
 *       세대 키가 없으면(처음 · 지워짐 · 새 캐시) 0 이 아니라 지금 시각(epoch ms)으로 시작한다 — 사라졌다 다시 생긴 키가 예전보다 큰 번호가
 *       되게(memcached 의 namespace 키 권장과 같다). 그래서 옛 세대의 값을 다시 집지 않는다. 이 보장은 키를 만든 뒤 흐른 ms 가 그동안 올린 횟수와
 *       인스턴스 사이 시계 차를 더한 것보다 클 때 선다. 키가 없을 때 조회는 키를 만드는 쓰기다 — 쓰기가 거부되면(메모리 부족 · 읽기 전용) 그 호출이
 *       실패해 이번 조회는 우회하고, 실패가 이어지면 이 캐시의 차단기가 열려 그동안 이 서비스의 모든 세대가 우회할 수 있다. 평소 조회는 키가 있어 GET 뿐이다
 *       (미룬 올리기 · 복원 감지 때만 쓴다).</li>
 *   <li><b>복원</b>: Redis 를 스냅샷으로 되돌리면(복제가 늦은 장애 조치도 같다) 세대 키도 그때 번호로 돌아가고, 그 번호의 옛 값이 같이 돌아온다. 떠 있는 인스턴스는 본 번호보다 작은
 *       세대를 보면 그 세대를 본 번호 + 1 로 올리고 그 번호로 읽는다(복원된 Redis 에는 그보다 큰 세대의 값이 없다) — 돌아온 옛 값을 내지 않고, 옛 세대로 넣은 몫도 정리한다.
 *       스냅샷보다 큰 번호를 본 적 없는 인스턴스(복원 뒤 새로 뜸 · 스냅샷 뒤 그 세대를 읽지 않음)는 알아채지 못하고 돌아온 옛 값을 TTL 동안 낸다. 그래서 복원(또는 세대 키를 손댄) 뒤에는 운영자가 세대 키를 지우지 말고 지금 시각보다
 *       작으면 지금 시각으로 올린다 — {@code <서비스>:cache:gen:*} 키마다
 *       {@code EVAL "local v=tonumber(redis.call('GET',KEYS[1]) or '0') if v<tonumber(ARGV[1]) then redis.call('SET',KEYS[1],ARGV[1]) end" 1 <키> <epoch ms>}.
 *       값 키는 지우지 않아도 된다(새 세대에는 안 보이고 TTL 로 사라진다). 같은 Redis 의 다른 키(대기열 · 토큰 표식)는 건드리지 않는다.</li>
 *   <li><b>시계</b>: 값의 만료는 앱 벽시계로 정한 절대 시각(PXAT)이다 — 앱 시계가 Redis 보다 빠르면 그만큼 더 살고, 느리면 덜 산다. 그래서 신선도의
 *       최악은 TTL + 앱 · Redis 시계 차다(둘 다 AWS 시간 동기화를 쓴다는 가정).</li>
 *   <li><b>배포</b>: 값은 JSON 이고 키에 그 타입 모양의 지문({@link Shape})을 넣는다. 모양이 다른 버전(blue/green 이 겹치는 동안)은 서로의 키를
 *       읽지 않는다 — 읽으면 새 칸이 null 로 조용히 비어 나간다. 지문이 같은데 못 읽는 값은 없는 것으로 보고 다시 채운다.</li>
 * </ul>
 * 값은 바뀌지 않는(불변) 객체여야 한다 — 같이 기다린 요청들이 한 객체를 나눠 쓴다. 지문은 레코드 칸 · enum 만 본다 — 값 타입에 Jackson 이름
 * 애너테이션(@JsonProperty 등)이나 레코드 아닌 클래스를 쓰면 캐시 이름에 버전을 붙여 바꾼다.
 * <p>운영 Redis 는 여러 서비스가 나눠 쓰는 단일 노드이고 메모리가 차면 TTL 있는 키부터 지운다(volatile-lru) — 토큰 폐기 표식도 TTL 이 있어
 * 같이 밀려날 수 있다. 그래서 캐시 키가 끝없이 늘 수 있는 조회(자유 검색어 · 깊은 페이지)는 쓰는 쪽이 캐시하지 않는다. 세대 키는 TTL 이 없어
 * 이 정책이 지우지 않는다(사람이 지우면 지금 시각으로 다시 시작한다).
 */
public final class ReadCache {

    private static final Logger log = LoggerFactory.getLogger(ReadCache.class);

    /** 차단기가 열린 뒤 Redis 를 부르지 않는 시간. */
    static final Duration OPEN_FOR = Duration.ofSeconds(10);
    /** 같은 키의 진행 중 조회를 같이 받는 쪽이 기다리는 상한. */
    static final Duration WAIT_FOR_LOAD = Duration.ofSeconds(5);
    /** 앱과 Redis 의 시계 차 여유 — 저장 몫을 값의 절대 만료보다 이만큼 더 잡는다({@link StoreBudget}). */
    static final Duration CLOCK_SKEW_MARGIN = Duration.ofSeconds(2);
    /** 캐시 하나가 인스턴스 하나에서 Redis 에 동시에 들고 있는 양의 기본 상한({@link StoreBudget}). */
    static final long DEFAULT_BUDGET_BYTES = 4L * 1024 * 1024;
    /** 읽은 지 TTL 의 이만큼(%)이 지난 값을 꺼내면 미리 갱신한다 — 리뷰(60초)면 48초, 상세 본문(10분)이면 8분. */
    static final int REFRESH_AFTER_PERCENT = 80;
    /** 넣지 못했거나 맡기지 못한 갱신을 기억하는 키 수 상한 — 넘으면 지난 것을 지우고, 그래도 차 있으면 더 기억하지 않는다. */
    static final int NOT_STORED_LIMIT = 10_000;
    /** Redis 에 넣는 모양({@link Stored})의 버전 — 키에 들어간다. */
    static final String FORMAT = "f1";

    /** 없는 세대를 지금 시각(ARGV[1], epoch ms)으로 만들고 지금 값을 돌려준다 — 동시에 처음 읽어도 한 값만 남는다. 평소 조회는 GET 만 한다(쓰기가 막혀도 읽게). */
    private static final RedisScript<String> CREATE_GENERATION = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'NX')
            return redis.call('GET', KEYS[1])
            """, String.class);
    /** 세대를 올린다 — 없으면 지금 시각(ARGV[1])에서 시작해 올린다. */
    private static final RedisScript<Long> BUMP_GENERATION = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then redis.call('SET', KEYS[1], ARGV[1]) end
            return redis.call('INCR', KEYS[1])
            """, Long.class);
    /** 세대를 ARGV[1] 보다 작으면 ARGV[1] 로 올리고 지금 값을 돌려준다 — 여러 인스턴스가 함께 해도 줄지 않는다. */
    private static final RedisScript<Long> RAISE_GENERATION = new DefaultRedisScript<>("""
            local v = tonumber(redis.call('GET', KEYS[1]) or '0')
            local floor = tonumber(ARGV[1])
            if v < floor then
              redis.call('SET', KEYS[1], ARGV[1])
              return floor
            end
            return v
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String prefix;
    private final CircuitBreaker breaker;
    private final ConcurrentHashMap<String, CompletableFuture<Object>> loading = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration waitForLoad;
    /** false 면 Redis 를 부르지 않고 늘 loader 로 답한다({@link #off}). */
    private final boolean enabled;
    /** 올리지 못한 세대 — Redis 를 다시 읽을 수 있게 되면 그때 올린다({@link #retryPendingBumps}). */
    private final Set<String> pendingBumps = ConcurrentHashMap.newKeySet();
    /** 캐시(spec) · 세대마다 이 인스턴스가 본 번호 중 가장 큰 것 — 오른 것을 처음 보면 그 캐시가 옛 세대로 넣은 키를 지우고 저장 몫을 돌려준다. */
    private final ConcurrentHashMap<String, Long> seenGenerations = new ConcurrentHashMap<>();
    /**
     * 세대마다 이 인스턴스의 어느 캐시든 본 번호 중 가장 큰 것 — 복원 판정용. 한 세대를 여러 캐시가 나눠 쓰므로 캐시별 기록으로 판정하면, 그 캐시가 늦게
     * 읽은 동안 다른 캐시가 더 큰 번호를 본 뒤 복원된 경우를 놓친다.
     */
    private final ConcurrentHashMap<String, Long> highestGenerations = new ConcurrentHashMap<>();
    /** 미리 갱신을 맡는 실행기 — null 이면 미리 갱신하지 않는다. */
    private final Executor refresher;
    private final ReadCacheEvents events;
    /** 실행기에 맡겨 둔 갱신의 자리 — 같은 키를 두 번 맡기지 않는다. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    /** 갱신 결과를 넣지 못했거나 갱신을 맡기지 못한 키와 그때 들고 있던 값의 만료 시각 — 그때까지 다시 맡기지 않는다. */
    private final ConcurrentHashMap<String, Instant> notStoredUntil = new ConcurrentHashMap<>();
    /** 세대마다 이 인스턴스가 Redis 에서 그 번호를 마지막으로 읽은 시각 — 장애 중 사본을 믿는 시간({@link OutageCopies})의 기준. */
    private final ConcurrentHashMap<String, Instant> confirmedAt = new ConcurrentHashMap<>();
    /** 세대마다 이 인스턴스가 마지막으로 올리려 한 시각 — 그 전에 읽기 시작한 사본은 쓰지 않는다(올리기가 미뤄져 번호가 그대로여도). */
    private final ConcurrentHashMap<String, Instant> bumpedAt = new ConcurrentHashMap<>();

    /** @param service 키 앞에 붙는 서비스 이름(catalog · order) — 같은 Redis 를 나눠 쓰는 서비스끼리 키가 겹치지 않게 */
    public ReadCache(StringRedisTemplate redis, JsonMapper json, String service) {
        this(redis, json, service, null, ReadCacheEvents.NONE);
    }

    /**
     * @param refresher 미리 갱신을 맡는 실행기(크기가 정해진 것) — null 이면 미리 갱신하지 않는다
     * @param events    요청마다의 결과(지표)
     */
    public ReadCache(StringRedisTemplate redis, JsonMapper json, String service, Executor refresher, ReadCacheEvents events) {
        this(redis, json, service, defaultBreaker(), true, Clock.systemUTC(), WAIT_FOR_LOAD, refresher, events);
    }

    private static CircuitBreakerConfig defaultBreaker() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(OPEN_FOR)
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
    }

    ReadCache(StringRedisTemplate redis, JsonMapper json, String service, CircuitBreakerConfig breakerConfig) {
        this(redis, json, service, breakerConfig, true, Clock.systemUTC(), WAIT_FOR_LOAD);
    }

    ReadCache(StringRedisTemplate redis, JsonMapper json, String service, CircuitBreakerConfig breakerConfig, boolean enabled, Clock clock,
              Duration waitForLoad) {
        this(redis, json, service, breakerConfig, enabled, clock, waitForLoad, null, ReadCacheEvents.NONE);
    }

    ReadCache(StringRedisTemplate redis, JsonMapper json, String service, CircuitBreakerConfig breakerConfig, boolean enabled, Clock clock,
              Duration waitForLoad, Executor refresher, ReadCacheEvents events) {
        this.refresher = refresher;
        this.events = Objects.requireNonNull(events, "events");
        this.enabled = enabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.waitForLoad = Objects.requireNonNull(waitForLoad, "waitForLoad");
        this.redis = enabled ? Objects.requireNonNull(redis, "redis") : redis;
        this.json = Objects.requireNonNull(json, "json");
        this.prefix = Objects.requireNonNull(service, "service") + ":cache:";
        this.breaker = CircuitBreaker.of(service + "-read-cache", breakerConfig);
    }

    /**
     * 끈 캐시 — 늘 loader 로 답하고 Redis 를 부르지 않는다. 시험이 DB 를 JDBC 로 직접 고치는 경우처럼 세대를 올리지 않는 쓰기를 쓰는 곳에서 쓴다.
     */
    public static ReadCache off(JsonMapper json, String service) {
        return off(null, json, service);
    }

    /**
     * 끈 캐시 — 조회는 {@link #off(JsonMapper, String)} 와 같고, {@link #bumpNow} 만 이 Redis 로 세대를 올린다. 운영에서 캐시를 끈 인스턴스용이다 —
     * 같은 큐를 받는 다른 인스턴스는 켜져 있을 수 있어, 끈 쪽이 받은 변경을 버리면 켠 쪽의 캐시가 비워지지 않는다.
     * {@link #bumpAfterCommit}(이 서비스 자신의 쓰기)은 끈 캐시에서 여전히 아무것도 하지 않는다 — 켠 인스턴스와 섞여 도는 동안 · 다시 켠 직후에는
     * 끈 인스턴스가 받은 쓰기의 옛 값이 그 캐시의 TTL 동안 보일 수 있다.
     *
     * @param redis null 이면 {@link #off(JsonMapper, String)} 와 같다
     */
    public static ReadCache off(StringRedisTemplate redis, JsonMapper json, String service) {
        return new ReadCache(redis, json, service, CircuitBreakerConfig.ofDefaults(), false, Clock.systemUTC(), WAIT_FOR_LOAD);
    }

    /** 캐시 하나의 이름 · 값 타입 · TTL. 이름과 타입 지문이 키에 들어간다. 메모리 예산은 {@link #DEFAULT_BUDGET_BYTES}. */
    public <T> Spec<T> spec(String name, Class<T> type, Duration ttl) {
        return spec(name, json.constructType(type), ttl, DEFAULT_BUDGET_BYTES);
    }

    public <T> Spec<T> spec(String name, tools.jackson.core.type.TypeReference<T> type, Duration ttl) {
        return spec(name, json.constructType(type), ttl, DEFAULT_BUDGET_BYTES);
    }

    /** @param budgetBytes 이 인스턴스가 이 캐시로 Redis 에 동시에 들고 있는 양의 상한({@link StoreBudget}) */
    public <T> Spec<T> spec(String name, Class<T> type, Duration ttl, long budgetBytes) {
        return spec(name, json.constructType(type), ttl, budgetBytes);
    }

    public <T> Spec<T> spec(String name, tools.jackson.core.type.TypeReference<T> type, Duration ttl, long budgetBytes) {
        return spec(name, json.constructType(type), ttl, budgetBytes);
    }

    /**
     * 장애 중 사본을 켠 캐시 — Redis 를 못 읽으면 우회하기 전에 이 인스턴스의 사본으로 답한다({@link OutageCopies}). 사본의 양은 저장 상한
     * ({@link #DEFAULT_BUDGET_BYTES})과 같은 수의 JSON 글자로 묶는다.
     *
     * @param heldDuringOutage 이 인스턴스가 Redis 에서 세대를 마지막으로 읽은 뒤 사본을 믿는 시간 — 장애 중 다른 인스턴스의 변경이 늦게 보이는 상한
     */
    public <T> Spec<T> spec(String name, Class<T> type, Duration ttl, Duration heldDuringOutage) {
        return spec(name, json.constructType(type), ttl, DEFAULT_BUDGET_BYTES, Objects.requireNonNull(heldDuringOutage, "heldDuringOutage"));
    }

    public <T> Spec<T> spec(String name, tools.jackson.core.type.TypeReference<T> type, Duration ttl, Duration heldDuringOutage) {
        return spec(name, json.constructType(type), ttl, DEFAULT_BUDGET_BYTES, Objects.requireNonNull(heldDuringOutage, "heldDuringOutage"));
    }

    private <T> Spec<T> spec(String name, JavaType type, Duration ttl, long budgetBytes) {
        return spec(name, type, ttl, budgetBytes, null);
    }

    private <T> Spec<T> spec(String name, JavaType type, Duration ttl, long budgetBytes, Duration heldDuringOutage) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        if (heldDuringOutage != null && (heldDuringOutage.isNegative() || heldDuringOutage.isZero())) {
            throw new IllegalArgumentException("heldDuringOutage must be positive: " + heldDuringOutage);
        }
        OutageCopies outage = heldDuringOutage == null ? null : new OutageCopies(heldDuringOutage, ttl, budgetBytes);
        return new Spec<>(name, type, Shape.fingerprint(type), ttl, new StoreBudget(budgetBytes, System::nanoTime), outage);
    }

    /**
     * 캐시하지 않는 조회 — loader 로 답하고 시작 시각을 읽은 시각으로 준다(캐시를 거친 값과 같은 기준으로 max-age 를 정하게).
     * 키가 끝없이 늘 수 있는 조회(자유 검색어 · 깊은 페이지)처럼 쓰는 쪽이 넣지 않기로 한 조회에 쓴다.
     */
    public <T> Entry<T> uncached(Supplier<T> loader) {
        Instant start = clock.instant();
        return new Entry<>(loader.get(), start);
    }

    /** {@link #entry} 의 값만. */
    public <T> T get(Spec<T> spec, String generation, String id, Supplier<T> loader) {
        return entry(spec, generation, id, loader).value();
    }

    /** 조회 경로를 받는 loader 로 — {@link #entry(Spec, String, String, Function, Predicate)} 의 값만. null 이 아닌 값은 넣는다. */
    public <T> T get(Spec<T> spec, String generation, String id, Function<LoadPath, T> loader) {
        return entry(spec, generation, id, loader, value -> true).value();
    }

    /** null 이 아닌 값은 넣는다. */
    public <T> Entry<T> entry(Spec<T> spec, String generation, String id, Supplier<T> loader) {
        return entry(spec, generation, id, loader, value -> true);
    }

    /**
     * 세대 {@code generation} 의 키 {@code id} 값과 그 값을 DB 에서 읽은 시각. 없으면 loader 로 만들어 넣는다. loader 가 null 을 주면 넣지 않는다.
     * 세대 번호를 못 읽으면(Redis 장애) 캐시를 건너뛰고 loader 로 답한다 — 아무 번호나 쓰면 옛 값을 줄 수 있다. 세대는 읽었는데 값을 못 읽어도
     * (실패 · 차단기 열림) 같다 — 없음으로 보지 않고 우회한다.
     * 읽은 시각은 응답의 HTTP 캐시 시간({@link Entry#remaining})을 정하는 데 쓴다 — 앞단 캐시가 이 값을 TTL 넘게 들고 있지 않게.
     */
    public <T> Entry<T> entry(Spec<T> spec, String generation, String id, Supplier<T> loader, Predicate<T> storable) {
        return entry(spec, generation, id, path -> loader.get(), storable);
    }

    /**
     * {@link #entry(Spec, String, String, Supplier, Predicate)} 와 같고, loader 가 이번 조회의 경로({@link LoadPath})를 받는다 — 넣을 수도 있는
     * 채우기(FILL)와 넣지 않는 우회(BYPASS)에 원천을 다르게 물을 때 쓴다. 세대나 값을 Redis 에서 못 읽으면(실패 · 차단기 열림) 우회다.
     */
    public <T> Entry<T> entry(Spec<T> spec, String generation, String id, Function<LoadPath, T> loader, Predicate<T> storable) {
        if (!enabled) {
            Instant start = clock.instant();
            return new Entry<>(loader.apply(LoadPath.BYPASS), start);
        }
        Long current = readGeneration(generation);
        if (current == null) {
            return bypass(spec, generation, null, id, loader, storable);
        }
        releaseOlderGenerations(spec, generation, current);
        String key = prefix + spec.name() + ":" + FORMAT + "." + spec.fingerprint() + ":" + generation + "." + current + ":" + id;
        String cached;
        try {
            cached = breaker.executeSupplier(() -> redis.opsForValue().get(key));
        } catch (CallNotPermittedException e) {
            return bypass(spec, generation, current, id, loader, storable);
        } catch (RuntimeException e) {
            // 값을 못 읽은 것은 "없음" 이 아니다 — 채우기로 가면 넣지도 못할 값을 원천에 지금 값으로 묻는다
            log.warn("읽기 캐시 Redis 호출 실패 — 캐시 없이 간다: {}", e.toString());
            return bypass(spec, generation, current, id, loader, storable);
        }
        boolean warned = false;
        if (cached != null) {
            Entry<T> entry = parseStored(cached, spec, key, false);
            if (entry != null) {
                keepCopy(spec, generation, current, id, entry, cached.length());
                boolean submitted = refresher != null
                        && !clock.instant().isBefore(entry.loadedAt().plus(spec.ttl().multipliedBy(REFRESH_AFTER_PERCENT).dividedBy(100)))
                        && refreshAhead(key, id, spec, entry.loadedAt(), loader, storable, generation, current);
                events.served(spec.name(), submitted ? Outcome.HIT_REFRESH : Outcome.HIT);
                return entry;
            }
            warned = true;   // parseStored 는 있는 값을 못 읽으면 늘 경고하고 null 을 낸다 — 다시 보기가 같은 경고를 또 남기지 않게 넘긴다
        }
        // 완성된 키(세대 번호 포함)로 줄 세운다 — 세대가 오른 뒤 온 요청이 옛 세대의 진행 중 조회를 같이 받지 않게
        return load(key, key, id, spec, loader, storable, generation, current, warned);
    }

    /**
     * Redis 를 못 쓸 때 — 넣지 않고 loader 로 답한다(우회). 우회 자리(down|)는 우회끼리만 같이 받는다 — 정상 세대의 진행 중 조회와 섞이지 않게.
     * 채우기 자리를 잡은 뒤 다시 보기가 우회로 바뀐 경우는 이 메서드를 거치지 않고 그 채우기 자리에서 우회하므로, 그 자리의 대기자는 우회 결과를
     * 받는다(클래스 문서의 몰림). 세대를 읽었으면 그 번호까지 자리에 넣는다 — 옛 세대에서 시작한 우회를 새 세대를 읽은 요청이 같이 받지 않게.
     *
     * 사본을 켠 캐시는 먼저 이 인스턴스의 사본을 본다({@link #heldCopy}) — 쓸 수 있으면 원천을 부르지 않는다.
     *
     * @param current 읽은 세대 번호, 못 읽었으면 null
     */
    private <T> Entry<T> bypass(Spec<T> spec, String generation, Long current, String id, Function<LoadPath, T> loader, Predicate<T> storable) {
        Entry<T> held = heldCopy(spec, generation, current, id);
        if (held != null) {
            return held;
        }
        String slot = "down|" + spec.name() + ":" + generation + (current == null ? "" : "." + current) + ":" + id;
        return load(slot, null, null, spec, loader, storable, generation, -1, false);
    }

    /**
     * Redis 를 못 읽을 때 답할 사본 — 없거나 못 쓰면 null(우회). 쓸 수 있는 사본은 아래를 모두 지난 것이다.
     * <ul>
     *   <li>세대 번호가 맞다 — 세대를 읽었으면 그 번호, 못 읽었으면 이 인스턴스가 본 가장 큰 번호({@link #lastSeenGeneration}).
     *       못 읽었을 때는 그 번호를 Redis 에서 마지막으로 읽은 지 사본을 믿는 시간(heldDuringOutage) 안이어야 한다 — 그 뒤의 올리기는 이 인스턴스가 모른다.</li>
     *   <li>값의 TTL 안이다 — 평소 Redis 에 있었을 값보다 오래된 값을 내지 않는다(읽은 시각은 그대로 줘 max-age 도 줄어든다).</li>
     *   <li>이 인스턴스가 그 세대를 마지막으로 올리려 한 뒤에 읽기 시작한 값이다 — 장애 중 이 인스턴스가 바꾼 것(올리기가 미뤄짐)은 바로 빠진다.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private <T> Entry<T> heldCopy(Spec<T> spec, String generation, Long current, String id) {
        OutageCopies outage = spec.outage();
        if (outage == null) {
            return null;
        }
        Instant now = clock.instant();
        long number;
        if (current != null) {
            number = current;
        } else {
            Long seen = highestGenerations.get(generation);
            Instant confirmed = confirmedAt.get(generation);
            if (seen == null || confirmed == null || now.isAfter(confirmed.plus(outage.heldFor))) {
                return null;
            }
            number = seen;
        }
        Copy copy = outage.copies.getIfPresent(generation + ":" + id);
        if (copy == null || copy.generationNumber() != number || !readAfterBump(generation, copy.entry())
                || !now.isBefore(copy.entry().loadedAt().plus(spec.ttl()))) {
            return null;
        }
        events.served(spec.name(), Outcome.HELD);
        return (Entry<T>) copy.entry();
    }

    /**
     * 사본을 남긴다 — Redis 에서 읽은 값과 원천에서 읽어 넣으려 한 값(넣지 못했어도). 이 인스턴스가 그 세대를 올리려 하기 전에 읽기 시작한 값도
     * 남지만 꺼낼 때 거른다({@link #heldCopy}) — 남긴 뒤에 올리기가 와도 같이 걸러지게 한 곳에서만 본다.
     *
     * @param weight 값의 JSON 글자 수 — 사본 양의 상한을 센다
     */
    private void keepCopy(Spec<?> spec, String generation, long number, String id, Entry<?> entry, int weight) {
        OutageCopies outage = spec.outage();
        if (outage == null || id == null) {
            return;
        }
        String copyKey = generation + ":" + id;
        Copy held = outage.copies.getIfPresent(copyKey);
        if (held != null && held.generationNumber() == number && held.entry().loadedAt().equals(entry.loadedAt())) {
            return;   // 같은 값 — 히트마다 다시 쓰지 않는다
        }
        outage.copies.put(copyKey, new Copy(number, entry, weight));
    }

    private boolean readAfterBump(String generation, Entry<?> entry) {
        Instant bumped = bumpedAt.get(generation);
        return bumped == null || entry.loadedAt().isAfter(bumped);
    }

    /**
     * 그 세대의 지금 번호 — 조회({@link #entry})가 키에 넣는 번호와 같다(미룬 올리기 · 복원 감지를 같이 거친다). 이 클래스 밖에 두는 캐시(인스턴스 메모리)가
     * 같은 비우기를 따를 때 키에 넣는다. 끈 캐시이거나 Redis 에서 못 읽으면(실패 · 차단기 열림) 비어 있다 — 그때는 캐시하지 않고 원천을 읽는다.
     */
    public OptionalLong generation(String generation) {
        if (!enabled) {
            return OptionalLong.empty();
        }
        Long current = readGeneration(generation);
        return current == null ? OptionalLong.empty() : OptionalLong.of(current);
    }

    /**
     * 이 인스턴스가 그 세대에서 본 가장 큰 번호 — Redis 를 부르지 않는다. 본 적 없으면 비어 있다. {@link #generation} 을 못 읽을 때 바깥 캐시가
     * 마지막으로 본 키로만 찾을 때 쓴다(그동안의 올리기는 모른다 — 그 캐시의 TTL 이 늦게 보이는 상한이다).
     */
    public OptionalLong lastSeenGeneration(String generation) {
        Long seen = highestGenerations.get(generation);
        return seen == null ? OptionalLong.empty() : OptionalLong.of(seen);
    }

    /** 세대의 지금 번호를 읽는다 — 못 읽으면 null. 미룬 올리기를 먼저 해 보고, 본 번호보다 작으면(복원) 올려서 읽는다. */
    private Long readGeneration(String generation) {
        // 본 번호는 세대를 읽기 전에 꺼낸다 — 읽은 뒤 꺼내면 그사이 다른 스레드가 더 큰 번호를 본 것과 섞여, 정상 올리기 뒤에도 되돌아간 것으로 본다
        Long seenBefore = highestGenerations.get(generation);
        Long current = call(() -> currentGeneration(generation));
        if (current == null) {
            return null;
        }
        if (!pendingBumps.isEmpty() && retryPendingBumps(generation)) {
            current = call(() -> currentGeneration(generation));   // 방금 올린 세대 — 새 번호로 읽는다
            if (current == null) {
                return null;
            }
        }
        current = raiseIfRolledBack(generation, seenBefore, current);
        if (current == null) {
            return null;
        }
        highestGenerations.merge(generation, current, Math::max);
        confirmedAt.put(generation, clock.instant());
        return current;
    }

    /**
     * loader 가 받는 조회 경로. 넣을지는 loader 가 끝난 뒤 정해지고(저장 조건 · 예산 · Redis 실패로 못 넣을 수 있다) 이것은 그 약속이 아니다 —
     * 어느 길로 원천에 묻는지만 말한다.
     */
    public enum LoadPath {
        /** 캐시가 비었거나 미리 갱신 — 결과가 저장 조건을 지나면 넣으려 한다. */
        FILL,
        /** 캐시를 쓸 수 없다(끔 · Redis 실패 · 차단기 열림) — 결과는 이 요청에만 쓰고 넣지 않는다. 그 사이 Redis 가 돌아와도 넣지 않는다. */
        BYPASS
    }

    /**
     * 세대 번호를 올린다 — 그 세대를 키에 넣은 값이 전부 다음 조회부터 새로 채워진다. 트랜잭션 안이면 그 트랜잭션의 커밋 뒤에(한 트랜잭션에서 같은
     * 세대를 여러 번 불러도 한 번), 밖이면 바로 한다. 안쪽 새 트랜잭션(REQUIRES_NEW)은 자기 커밋에 붙는다 — 지금 트랜잭션의 동기화 목록에서 찾는다.
     * 실패해도 던지지 않는다(수정은 이미 커밋됐다) — {@link #bump} 가 다시 시도하고 끝내 못 올리면 나중에 다시 한다.
     */
    public void bumpAfterCommit(String generation) {
        if (!enabled) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bump(generation);
            return;
        }
        for (TransactionSynchronization registered : TransactionSynchronizationManager.getSynchronizations()) {
            if (registered instanceof BumpOnCommit mine && mine.owner == this) {
                mine.generations.add(generation);
                return;
            }
        }
        BumpOnCommit created = new BumpOnCommit(this);
        created.generations.add(generation);
        TransactionSynchronizationManager.registerSynchronization(created);
    }

    /**
     * 세대 번호를 지금 올리고, 못 올렸으면 던진다 — 다른 서비스의 변경을 메시지로 받아 올리는 처리기용이다(던지면 메시지를 지우지 않아 다시 받아
     * 다시 올린다). 트랜잭션과 상관없이 바로 한다. 못 올린 것은 {@link #bumpAfterCommit} 처럼 미뤄도 둔다 — 다시 받기 전에 이 인스턴스의 조회가
     * 먼저 올릴 수 있고, 그러면 두 번 오른다(값을 한 번 더 채울 뿐이다 — 끈 캐시는 조회가 Redis 를 읽지 않으므로 다시 받은 메시지로만 올린다).
     * 끈 캐시도 Redis 를 받았으면({@link #off(StringRedisTemplate, JsonMapper, String)})
     * 올린다 — 받은 메시지는 한 인스턴스만 처리하므로, 끈 인스턴스가 버리면 켠 인스턴스의 캐시가 비워지지 않는다. Redis 가 없는 끈 캐시면 아무것도 하지 않는다.
     *
     * @throws IllegalStateException 올리지 못했다(차단기가 열렸거나 Redis 실패). 끈 캐시의 차단기는 결과를 세지 않아 열리지 않는다 — Redis 장애 중에는
     *                               부를 때마다 Redis 제한 시간을 기다린 뒤 던진다
     */
    public void bumpNow(String generation) {
        if (redis != null && !bump(generation)) {
            throw new IllegalStateException("읽기 캐시 세대를 올리지 못했다 generation=" + generation);
        }
    }

    /** 한 트랜잭션의 커밋 뒤에 그 트랜잭션에서 부른 세대를 한 번씩 올린다. */
    private static final class BumpOnCommit implements TransactionSynchronization {

        private final ReadCache owner;
        private final Set<String> generations = new LinkedHashSet<>();

        private BumpOnCommit(ReadCache owner) {
            this.owner = owner;
        }

        @Override
        public void afterCommit() {
            generations.forEach(owner::bump);
        }
    }

    /**
     * 세대를 올린다. 실패를 버리지 않는다 — 차단기는 인스턴스마다 따로라, 이 인스턴스에서 실패한 올리기를 버리면 다른 인스턴스가 옛 값을 TTL 동안 낸다.
     * 한 번 해 보고 안 되면 {@link #pendingBumps} 에 두고 이 인스턴스가 Redis 를 다시 읽는 첫 조회에서 올린다(ERROR 로그). 차단기가 열려 있으면
     * 부르지 않고 바로 미룬다 — 커밋 뒤 콜백은 DB 커넥션을 쥔 채 돌므로(spring-tx 는 afterCommit 뒤에 커넥션을 돌려준다) 멈춘 Redis 를 기다리지 않게.
     * 시간 초과는 결과를 모르는 실패다 — 서버가 늦게 실행해 한 번 더 오를 수 있다(실측: 멈춘 Redis 를 풀자 시간 초과된 INCR 이 들어갔다).
     * 더 오르는 것은 값을 한 번 더 채울 뿐이라 그대로 둔다.
     *
     * @return 이번에 올렸으면 true, 미뤘으면 false
     */
    private boolean bump(String generation) {
        bumpedAt.merge(generation, clock.instant(), (before, now) -> now.isAfter(before) ? now : before);
        if (!breaker.tryAcquirePermission()) {
            pendingBumps.add(generation);
            log.error("읽기 캐시 차단기가 열려 세대 올리기를 미룬다 — 그때까지 옛 값이 보일 수 있다 generation={}", generation);
            return false;
        }
        breaker.releasePermission();   // 허락만 물었다 — 결과는 차단기에 세지 않는다
        try {
            incrementGeneration(generation);
            pendingBumps.remove(generation);
            return true;
        } catch (RuntimeException e) {
            pendingBumps.add(generation);
            log.error("읽기 캐시 세대를 올리지 못해 나중에 다시 올린다 — 그때까지 옛 값이 보일 수 있다 generation={}", generation, e);
            return false;
        }
    }

    /**
     * 미룬 세대를 올린다(Redis 를 방금 읽은 조회에서, 차단기를 거쳐). 집합에서 먼저 뺀 스레드만 올린다 — 복구 직후 여러 조회가 겹쳐도 세대마다 한 번. 실패하면 다시 넣는다.
     * generation 을 이번에 올렸으면 true.
     */
    private boolean retryPendingBumps(String generation) {
        boolean bumpedThis = false;
        for (String pending : List.copyOf(pendingBumps)) {
            if (!pendingBumps.remove(pending)) {
                continue;   // 다른 조회가 가져갔다
            }
            try {
                // 조회 경로라 차단기를 거친다 — 실패가 이어지면 열려, 미룬 것이 있는 동안 조회마다 제한 시간을 기다리지 않게
                breaker.executeRunnable(() -> incrementGeneration(pending));
                bumpedThis |= pending.equals(generation);
            } catch (CallNotPermittedException e) {
                pendingBumps.add(pending);
                return bumpedThis;
            } catch (RuntimeException e) {
                pendingBumps.add(pending);
                log.error("미룬 읽기 캐시 세대 올리기가 또 실패해 다음 조회에서 다시 한다 generation={}", pending, e);
                return bumpedThis;
            }
        }
        return bumpedThis;
    }

    /**
     * 세대가 오른 것을 처음 보면 이 인스턴스가 옛 세대로 넣은 이 캐시의 키를 지우고, 지운 몫을 저장 상한에서 돌려준다 — 안 그러면 옛 값의 몫이 TTL 동안
     * 남아 예산이 찬 인스턴스가 그동안 새로 넣지 못한다. 지우지 못하면(Redis 실패) 몫도 남겨 둔다(값이 Redis 에 남아 있을 수 있다).
     */
    private void releaseOlderGenerations(Spec<?> spec, String generation, long current) {
        String seenKey = spec.name() + "|" + generation;
        Long seen = seenGenerations.get(seenKey);
        // 늦게 온 조회가 옛 번호로 되돌리지 않게 큰 쪽만 남긴다
        if (seenGenerations.merge(seenKey, current, Math::max) != current || seen == null || seen >= current) {
            return;
        }
        List<StoreBudget.Hold> older = spec.budget().olderThan(generation, current);
        if (older.isEmpty()) {
            return;
        }
        List<String> keys = older.stream().map(StoreBudget.Hold::key).toList();
        if (call(() -> redis.unlink(keys)) != null) {
            older.forEach(spec.budget()::release);
        }
    }

    CircuitBreaker.State breakerState() {
        return breaker.getState();
    }

    /** 시험용 — 차단기를 직접 연다. */
    CircuitBreaker breaker() {
        return breaker;
    }

    private long currentGeneration(String generation) {
        String key = prefix + "gen:" + generation;
        String value = redis.opsForValue().get(key);
        if (value == null) {
            value = Objects.requireNonNull(redis.execute(CREATE_GENERATION, List.of(key), String.valueOf(clock.millis())), "generation");
        }
        return Long.parseLong(value);
    }

    private void incrementGeneration(String generation) {
        redis.execute(BUMP_GENERATION, List.of(prefix + "gen:" + generation), String.valueOf(clock.millis()));
    }

    /**
     * 세대를 읽기 전에 이 인스턴스(어느 캐시든)가 그 세대에서 본 가장 큰 번호(seen)보다 작은 세대를 읽었으면(스냅샷 복원 · 세대 키를 손댐) 본 번호 + 1 로 올리고 그 번호를 돌려준다 —
     * 돌아온 옛 세대 키의 값을 내지 않게. 세대는 올리기로만 바뀌어 줄지 않으므로, 읽기 전에 본 번호보다 작으면 되돌아간 것이다. 다른 인스턴스와
     * 겹쳐도 줄지 않는다(작을 때만 올린다 — 본 번호가 더 큰 인스턴스는 다시 알아채 더 올린다). 못 올리면 null(우회).
     */
    private Long raiseIfRolledBack(String generation, Long seen, long current) {
        if (seen == null || current >= seen) {
            return current;
        }
        long floor = seen + 1;
        Long raised = call(() -> redis.execute(RAISE_GENERATION, List.of(prefix + "gen:" + generation), String.valueOf(floor)));
        log.warn("읽기 캐시 세대가 본 번호보다 작다(복원 · 세대 키를 손댐) — 올려서 읽는다 generation={} seen={} current={} raised={}",
                generation, seen, current, raised);
        return raised;
    }

    /**
     * 같은 자리(slot)의 loader 를 이 인스턴스에서 한 번만 부르고, 나머지는 그 결과(또는 예외)를 같이 받는다. key 가 null 이면 넣지 않는다.
     * 같이 받는 쪽은 {@link #WAIT_FOR_LOAD} 까지만 기다리고 넘으면 503(다시 시도) — 멈춘 조회 하나에 같은 상품 요청이 전부 묶이지 않게.
     * 넘었다고 각자 DB 로 가게 풀지 않는다(DB 가 느린 때 부하가 그만큼 는다). 진행 중인 조회는 그대로 두고 끝나면 자리가 빈다.
     *
     * @param warned 첫 조회에서 못 읽는 값을 보고 이미 경고했는가 — 그러면 다시 보기에서 못 읽어도(이유가 달라도) debug 로만 남긴다
     */
    @SuppressWarnings("unchecked")
    private <T> Entry<T> load(String slot, String key, String id, Spec<T> spec, Function<LoadPath, T> loader, Predicate<T> storable,
                              String generation, long generationNumber, boolean warned) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = loading.putIfAbsent(slot, mine);
        if (running != null) {
            events.served(spec.name(), Outcome.WAIT);
            long started = System.nanoTime();
            try {
                return (Entry<T>) running.get(waitForLoad.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
            } catch (TimeoutException e) {
                log.warn("읽기 캐시 — 같은 키의 조회가 {} 안에 끝나지 않아 503 slot={}", waitForLoad, slot);
                throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
            } finally {
                events.waited(spec.name(), Duration.ofNanos(System.nanoTime() - started));
            }
        }
        try {
            LoadPath path = key == null ? LoadPath.BYPASS : LoadPath.FILL;
            if (key != null) {
                // 자리를 잡기 전에 값이 없음을 봤어도, 그사이 앞 조회가 넣고 자리를 비웠을 수 있다 — 원천을 부르기 전에 한 번 더 본다.
                // 자리를 잡은 뒤의 일은 모두 이 try 안이다 — 무엇이 던져도 같이 기다리는 쪽을 끝내고 자리를 비운다.
                // 다시 보기를 못 하면(Redis 실패 · 차단기) 없음이 아니다 — 첫 읽기 실패처럼 우회한다(넣지 않을 값을 채우기로 원천에 묻지 않게)
                String cached;
                try {
                    cached = breaker.executeSupplier(() -> redis.opsForValue().get(key));
                } catch (CallNotPermittedException e) {
                    cached = null;
                    path = LoadPath.BYPASS;
                } catch (RuntimeException e) {
                    log.warn("읽기 캐시 Redis 호출 실패 — 캐시 없이 간다: {}", e.toString());
                    cached = null;
                    path = LoadPath.BYPASS;
                }
                if (path == LoadPath.BYPASS) {
                    // 사본이 있으면 그것을 이 자리의 결과로 준다 — 붙어 기다린 요청도 원천을 기다리지 않는다
                    Entry<T> held = heldCopy(spec, generation, generationNumber, id);
                    if (held != null) {
                        mine.complete(held);
                        return held;
                    }
                }
                Entry<T> stored = parseStored(cached, spec, key, warned);
                if (stored != null) {
                    keepCopy(spec, generation, generationNumber, id, stored, cached.length());
                    events.served(spec.name(), Outcome.HIT);
                    mine.complete(stored);
                    return stored;
                }
            }
            events.served(spec.name(), Outcome.LOAD);
            Instant loadedAt = clock.instant();   // 읽기 전 시각 — 응답의 나이를 짧게 잡지 않게
            Entry<T> entry = new Entry<>(loader.apply(path), loadedAt);
            mine.complete(entry);
            if (path == LoadPath.FILL && entry.value() != null && storable.test(entry.value())) {
                store(key, id, spec, entry, generation, generationNumber);
            }
            return entry;
        } catch (Throwable e) {   // 검사 예외를 몰래 던지는 loader 도 기다린 쪽에 알린다(LocalReadCache 와 같다)
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(slot, mine);
        }
    }

    /**
     * 넣어 둔 값을 푼다 — 없거나, 모양이 다르거나, 비었으면(JSON null · 값 null) 못 읽는 값으로 보고 null(다시 채운다). 이 클래스는 null 값을 넣지
     * 않으므로 null 은 밖에서 쓴 키다. 조회와 자리를 잡은 뒤의 다시 보기가 같이 쓴다 — 같은 값에 두 갈래가 다르게 답하지 않게. 못 읽은 이유는
     * 요청마다 많아야 한 번 경고로 남긴다 — 첫 조회에서 이미 경고한 요청의 다시 보기는 debug 로, 첫 조회에서 값이 없다가 다시 보기에서 처음 못 읽으면 경고로.
     *
     * @param warned 이 요청에서 이미 경고했는가
     */
    private <T> Entry<T> parseStored(String cached, Spec<T> spec, String key, boolean warned) {
        if (cached == null) {
            return null;
        }
        try {
            Stored<T> stored = json.readValue(cached, json.getTypeFactory().constructParametricType(Stored.class, spec.type()));
            if (stored == null || stored.value() == null) {
                unreadable(warned, key, "값이 비어 있다(null)");
                return null;
            }
            return new Entry<>(stored.value(), Instant.ofEpochMilli(stored.loadedAt()));
        } catch (JacksonException e) {
            unreadable(warned, key, e.toString());
            return null;
        }
    }

    private static void unreadable(boolean warned, String key, String reason) {
        if (warned) {
            log.debug("읽기 캐시 값을 못 읽어 다시 채운다(이미 경고함) key={}: {}", key, reason);
        } else {
            log.warn("읽기 캐시 값을 못 읽어 다시 채운다 key={}: {}", key, reason);
        }
    }

    /**
     * 같은 키의 다시 읽기를 실행기에 하나만 맡긴다. 이미 맡겼거나 누가 읽는 중이거나, 앞 갱신이 넣지 못했거나 맡기지 못한 값이 아직 살아 있으면 맡기지 않는다.
     *
     * @param seenLoadedAt 이 요청이 꺼낸 값의 읽은 시각 — 갱신이 돌 때 이보다 새 값이 들어와 있으면 읽지 않는다
     * @return 맡겼으면 true
     */
    private <T> boolean refreshAhead(String key, String id, Spec<T> spec, Instant seenLoadedAt, Function<LoadPath, T> loader, Predicate<T> storable,
                                     String generation, long generationNumber) {
        Instant blockedUntil = notStoredUntil.get(key);
        if (blockedUntil != null) {
            if (clock.instant().isBefore(blockedUntil)) {
                return false;
            }
            notStoredUntil.remove(key, blockedUntil);
        }
        if (loading.containsKey(key) || !refreshing.add(key)) {
            return false;
        }
        try {
            refresher.execute(() -> {
                try {
                    refresh(key, id, spec, seenLoadedAt, loader, storable, generation, generationNumber);
                } finally {
                    refreshing.remove(key);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            refreshing.remove(key);
            // 실행기가 찬 동안 그 키의 히트마다 다시 맡기며 예외를 만들고 거절을 요청 수만큼 세지 않게, 그 값이 만료될 때까지 다시 맡기지 않는다
            rememberNotStored(key, seenLoadedAt.plus(spec.ttl()));
            events.refreshed(spec.name(), RefreshResult.REJECTED);
            return false;
        } catch (RuntimeException e) {
            refreshing.remove(key);   // 맡기지 못했다 — 표식을 남기면 그 키는 다시 맡겨지지 않는다. 요청은 들고 있던 값으로 답한다
            rememberNotStored(key, seenLoadedAt.plus(spec.ttl()));
            log.warn("읽기 캐시 미리 갱신을 맡기지 못했다 cache={}", spec.name(), e);
            events.refreshed(spec.name(), RefreshResult.REJECTED);
            return false;
        } catch (Error e) {
            refreshing.remove(key);
            throw e;
        }
    }

    /**
     * 실행기에서 도는 다시 읽기 — "한 번만 읽기" 자리({@link #load} 의 slot = 완성된 키)를 같이 써서, 그사이 만료돼 들어온 요청은 이 결과를 기다린다.
     * 실패하면 기다린 쪽도 같은 예외를 받고(직접 읽기와 같다) Redis 의 값은 그대로 만료된다.
     */
    private <T> void refresh(String key, String id, Spec<T> spec, Instant seenLoadedAt, Function<LoadPath, T> loader, Predicate<T> storable,
                             String generation, long generationNumber) {
        // 지금 세대를 Redis 에서 다시 읽는다 — 맡길 때의 번호와 비교한다(이 인스턴스가 본 가장 큰 번호와 비교하면, 세대가 되돌아간 뒤
        // 다시 오른 번호를 조회가 쓰기 전까지 갱신이 모두 건너뛰어진다). 못 읽으면 읽지 않는다 — Redis 를 못 쓰는 동안 넣지도 못할 값을 원천에 묻지 않게.
        // 그 키는 막아 두지 않는다 — 한 번의 시간 초과로 값이 만료될 때까지 미리 갱신이 멈추지 않게. 차단기가 열린 장애 동안은 조회가 우회라 히트가
        // 없지만, 간헐 실패(차단기가 닫힌 채)에서는 히트마다 다시 맡긴다 — 키당 하나씩이고, 늘어나는 실행기 점유는 실패 횟수 × Redis 제한 시간이다
        Long current = call(() -> currentGeneration(generation));
        if (current == null) {
            events.refreshed(spec.name(), RefreshResult.SKIPPED_UNAVAILABLE);
            return;
        }
        if (current != generationNumber) {
            events.refreshed(spec.name(), RefreshResult.SKIPPED_NEWER);   // 큐에서 기다리는 사이 세대가 바뀌었다 — 그 세대 키는 아무도 읽지 않는다
            return;
        }
        String held;
        try {
            held = breaker.executeSupplier(() -> redis.opsForValue().get(key));
        } catch (CallNotPermittedException e) {
            events.refreshed(spec.name(), RefreshResult.SKIPPED_UNAVAILABLE);   // 차단기가 열렸다 — call() 처럼 조용히
            return;
        } catch (RuntimeException e) {
            log.warn("읽기 캐시 Redis 호출 실패 — 미리 갱신을 건너뛴다: {}", e.toString());
            events.refreshed(spec.name(), RefreshResult.SKIPPED_UNAVAILABLE);   // 세대를 못 읽을 때와 같다
            return;
        }
        if (newerThan(held, seenLoadedAt)) {
            events.refreshed(spec.name(), RefreshResult.SKIPPED_NEWER);   // 큐에서 기다리는 사이 만료돼 요청이 더 새 값을 넣었다
            return;
        }
        CompletableFuture<Object> mine = new CompletableFuture<>();
        if (loading.putIfAbsent(key, mine) != null) {
            events.refreshed(spec.name(), RefreshResult.SKIPPED_NEWER);   // 그사이 만료돼 요청이 직접 읽는 중이다
            return;
        }
        try {
            Instant loadedAt = clock.instant();
            Entry<T> entry = new Entry<>(loader.apply(LoadPath.FILL), loadedAt);
            mine.complete(entry);
            boolean stored = entry.value() != null && storable.test(entry.value()) && store(key, id, spec, entry, generation, generationNumber);
            if (stored) {
                events.refreshed(spec.name(), RefreshResult.STORED);
            } else {
                rememberNotStored(key, seenLoadedAt.plus(spec.ttl()));
                events.refreshed(spec.name(), RefreshResult.NOT_STORED);
            }
        } catch (Throwable e) {   // 검사 예외를 몰래 던지는 loader 도 기다린 쪽에 알린다
            mine.completeExceptionally(e);
            // 실패도 그 값이 만료될 때까지 다시 맡기지 않는다 — DB 가 느리게 실패하는 동안 TTL 마지막 구간 내내 같은 키로 실행기를 묶지 않게
            rememberNotStored(key, seenLoadedAt.plus(spec.ttl()));
            events.refreshed(spec.name(), RefreshResult.FAILED);
            if (e instanceof BusinessException) {
                log.debug("읽기 캐시 미리 갱신이 업무 오류로 끝났다 — 만료 뒤 요청이 직접 읽는다 cache={}: {}", spec.name(), e.toString());
            } else {
                log.warn("읽기 캐시 미리 갱신 실패 — 만료 뒤 요청이 직접 읽는다 cache={}", spec.name(), e);
            }
            if (e instanceof Error error) {
                throw error;   // 기다린 쪽에는 알렸다 — 메모리 부족 같은 것은 삼키지 않는다
            }
        } finally {
            loading.remove(key, mine);
        }
    }

    /** Redis 에 있던 값(cached)이 seen 보다 나중에 읽은 값인가. 없거나 모양이 다르면 아니라고 본다 — 갱신을 그대로 한다. */
    private boolean newerThan(String cached, Instant seen) {
        if (cached == null) {
            return false;
        }
        try {
            tools.jackson.databind.JsonNode loadedAt = json.readTree(cached).get("loadedAt");
            return loadedAt != null && Instant.ofEpochMilli(loadedAt.asLong()).isAfter(seen);
        } catch (JacksonException e) {
            return false;
        }
    }

    /** 넣지 못했거나 맡기지 못한 갱신의 키 — 들고 있던 값이 만료될 때까지. 많이 쌓이면(요청이 다시 오지 않은 키) 지난 것부터 지운다. */
    private void rememberNotStored(String key, Instant until) {
        if (notStoredUntil.size() >= NOT_STORED_LIMIT) {
            Instant now = clock.instant();
            notStoredUntil.values().removeIf(expiry -> !now.isBefore(expiry));
        }
        if (notStoredUntil.size() < NOT_STORED_LIMIT) {
            notStoredUntil.put(key, until);
        }
    }

    /** @return 보냈으면 true(결과를 모르는 시간 초과 포함) — JSON 실패 · 남은 시간 없음 · 저장 상한 · 차단기면 false */
    private <T> boolean store(String key, String id, Spec<T> spec, Entry<T> entry, String generation, long generationNumber) {
        String body;
        try {
            body = json.writeValueAsString(new Stored<>(entry.loadedAt().toEpochMilli(), entry.value()));
        } catch (JacksonException e) {
            log.warn("읽기 캐시에 넣을 값을 JSON 으로 못 바꿨다 key={}", key, e);
            return false;
        }
        // 남은 시간만큼만 둔다 — 읽는 데 걸린 시간도 TTL 에서 뺀다
        Duration left = spec.ttl().minus(Duration.between(entry.loadedAt(), clock.instant()));
        if (left.toMillis() <= 0) {
            return false;
        }
        // 넣지 못해도(저장 상한 · Redis 실패) 이 세대의 지금 값이다 — 사본은 남긴다
        keepCopy(spec, generation, generationNumber, id, entry, body.length());
        // 이 인스턴스가 이 캐시로 들고 있는 양이 상한을 넘으면 넣지 않는다(DB 로 답한다) — 나눠 쓰는 Redis 의 다른 키(토큰 폐기 표식 · 대기열)를 덜 밀어내게
        long charge = StoreBudget.charge(key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        // 만료는 절대 시각(PXAT)으로 준다 — 서버가 SET 을 늦게 처리해도 이 시각에 사라진다(늦게 처리된 만큼 더 살지 않는다).
        // 몫은 지금부터 같은 시간 + 시계 차 여유만큼 잡는다 — 결과를 모르는 실패(시간 초과)에서도 따로 셀 것이 없다
        long expiresAtEpochMillis = clock.millis() + left.toMillis();
        StoreBudget.Hold hold = spec.budget().reserve(charge, left.plus(CLOCK_SKEW_MARGIN), key, generation, generationNumber);
        if (hold == null) {
            spec.budget().warnSkipped(spec.name());
            return false;
        }
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            breaker.executeRunnable(() -> redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) connection ->
                    connection.stringCommands().set(keyBytes, bodyBytes,
                            org.springframework.data.redis.core.types.Expiration.unixTimestamp(expiresAtEpochMillis, TimeUnit.MILLISECONDS),
                            org.springframework.data.redis.connection.RedisStringCommands.SetOption.upsert())));
        } catch (CallNotPermittedException e) {
            spec.budget().release(hold);   // 차단기가 막아 보내지 않았다
            return false;
        } catch (RuntimeException e) {
            log.warn("읽기 캐시 Redis 호출 실패 — 캐시 없이 간다: {}", e.toString());   // 결과 모름 — 몫은 잡은 그대로(절대 만료라 그 안에 사라진다)
            return false;
        }
        return true;
    }

    /** Redis 호출 하나. 실패 · 차단기 열림이면 null — 호출하는 쪽이 캐시 없이 간다. */
    private <R> R call(Supplier<R> operation) {
        try {
            return breaker.executeSupplier(operation);
        } catch (CallNotPermittedException e) {
            return null;
        } catch (RuntimeException e) {
            log.warn("읽기 캐시 Redis 호출 실패 — 캐시 없이 간다: {}", e.toString());
            return null;
        }
    }

    /**
     * 값과 DB 에서 읽은 시각.
     *
     * @param loadedAt 이 값을 만든 loader 가 시작한 시각
     */
    public record Entry<T>(T value, Instant loadedAt) {

        /** 값만 바꾼 사본 — 읽은 시각은 그대로(응답 봉투로 감쌀 때). */
        public <R> Entry<R> map(java.util.function.Function<T, R> mapper) {
            return new Entry<>(mapper.apply(value), loadedAt);
        }

        /** 이 값을 TTL 안에서 더 들고 있어도 되는 시간 — 앞단 HTTP 캐시의 max-age 상한. 0 보다 작으면 0. */
        public Duration remaining(Duration ttl, Instant now) {
            Duration left = ttl.minus(Duration.between(loadedAt, now));
            return left.isNegative() ? Duration.ZERO : left;
        }
    }

    /** Redis 에 넣는 모양. 이 모양을 바꾸면 {@link #FORMAT} 을 올린다. */
    record Stored<T>(long loadedAt, T value) {
    }

    /**
     * @param fingerprint 값 타입 모양의 지문 — 레코드 칸 이름 · 타입, enum 상수가 바뀌면 달라진다
     * @param budget      이 캐시가 이 인스턴스에서 Redis 에 동시에 들고 있는 양의 상한
     * @param outage      장애 중 사본 — null 이면 끔(Redis 를 못 읽으면 늘 우회)
     */
    public record Spec<T>(String name, JavaType type, String fingerprint, Duration ttl, StoreBudget budget, OutageCopies outage) {
    }

    /**
     * Redis 를 못 읽을 때(실패 · 시간 초과 · 차단기 열림) 우회 대신 답할 이 인스턴스의 사본 — 캐시 하나에 하나. 조회가 Redis 에서 읽은 값과 원천에서
     * 읽어 넣으려 한 값을 세대 번호와 함께 키(세대 이름 · id)마다 하나 남긴다. 평소에는 남기기만 하고 Redis 호출 수는 그대로다.
     * <ul>
     *   <li><b>왜</b>: Redis 가 몇 초 늦어 차단기가 열리면 그 인스턴스의 조회가 {@link #OPEN_FOR} 동안 모두 우회해, 같은 키가 원천(DB · 다른 서비스)
     *       한 건 뒤에 줄을 섰다(부하 시험 10-22: 홍보 상세가 한 인스턴스에서만 대기 3,654건 · 중앙값 0.5~0.75초, 다른 인스턴스는 p99 0.5초 이하).</li>
     *   <li><b>늦게 보이는 상한</b>: 세대를 못 읽는 동안 다른 인스턴스가 올린 세대는 모른다 — 믿는 시간(heldDuringOutage)이 그 상한이다(Redis 에서 그 세대를
     *       마지막으로 읽은 시각부터). 값 자체는 TTL 을 넘겨 쓰지 않는다. 이 인스턴스가 올리려 한 세대(장애 중이라 미뤄져도)의 사본은 바로 버린다.</li>
     *   <li><b>양</b>: JSON 글자 수의 합으로 묶고(넘으면 오래 안 쓴 것부터 밀려난다) TTL 이 지나면 지운다. 객체로 풀린 값이라 실제 힙은 글자 수보다 크다.</li>
     *   <li>세대를 읽었는데 값만 못 읽었으면 그 번호의 사본을 쓴다(믿는 시간과 상관없다 — 번호가 지금 것이다).</li>
     * </ul>
     */
    public static final class OutageCopies {

        private final Duration heldFor;
        private final Cache<String, Copy> copies;

        OutageCopies(Duration heldFor, Duration ttl, long maxChars) {
            this.heldFor = heldFor;
            this.copies = Caffeine.newBuilder()
                    .maximumWeight(maxChars)
                    .weigher((String key, Copy copy) -> copy.weight())
                    .expireAfterWrite(ttl)
                    .build();
        }
    }

    /** 사본 하나 — 그 값을 읽은 세대 번호와 함께. */
    private record Copy(long generationNumber, Entry<?> entry, int weight) {
    }

    /**
     * 캐시 하나가 이 인스턴스에서 Redis 에 넣어 둔 값 중 아직 살아 있는(만료 전) 것의 합 상한. 넣을 때 만료 시각과 차지할 양을 적어 두고, 만료된 것을
     * 빼고 더해 상한을 넘으면 넣지 않는다(DB 로 답한다) — 입력 조합(필터 · 페이지)이 아무리 많아도 이 인스턴스가 동시에 들고 있는 양이 상한에 묶인다.
     * <ul>
     *   <li>차지할 양은 {@link #charge} — Valkey 9.1.2 에서 MEMORY USAGE 로 잰 값(키 · 값 외에 작은 값은 40~60B, 60KB 는 약 9%, 200KB 는 약 15%)보다
     *       크게 잡는다. 큰 값의 몫은 할당기(jemalloc)의 크기 구간이 두 배마다 4칸이라 최대 약 25% 다.</li>
     *   <li>값은 절대 만료 시각(PXAT = 앱 벽시계 지금 + 남은 TTL)으로 넣고, 몫은 넣기 전에 같은 시간 + {@link #CLOCK_SKEW_MARGIN} 만큼 잡는다 — 서버가
     *       SET 을 늦게 처리해도 값은 그 시각에 사라지므로, 결과를 모르는 실패(시간 초과)에서도 몫이 값보다 먼저 풀리지 않는다(세대가 오를 때의 예외는 아래). 앱과 Redis 의 시계 차가
     *       여유보다 크면 이 보장이 깨진다(둘 다 AWS 시간 동기화를 쓴다는 가정 — 운영에서 재지는 않았다). 차단기가 막아 보내지 않았을 때만 바로 돌려준다.</li>
     *   <li>경과 시간은 단조 증가 시간원(System.nanoTime)으로 잰다 — 벽시계를 앞으로 맞춰도 살아 있는 값의 몫이 풀리지 않는다. 응답 신선도(loadedAt)는
     *       벽시계로 따로 잰다.</li>
     *   <li>TTL 전에 지워지거나 밀려난 값은 만료 시각까지 그대로 센다(보수적). 같은 키를 다시 넣으면 둘 다 센다(보수적). 세대가 오르면 이 인스턴스가
     *       옛 세대로 넣은 키를 지우고 그 몫을 돌려준다 — 그 순간 진행 중이던 옛 세대 저장(지운 뒤에 SET 이 들어간 것)은 값이 TTL 까지 남는데 몫은
     *       이미 돌려줘 그만큼 덜 센다. 크기는 세대가 오르는 순간 진행 중이던 저장 수만큼이다.</li>
     *   <li><b>인스턴스 하나의 상한이다.</b> 재시작하면 0 에서 다시 센다 — 그때 Redis 에 남은 옛 값은 세지 않는다. 그래서 서비스 전체 점유는
     *       상한 × (떠 있는 인스턴스 + TTL 안에 내려간 인스턴스) 로 잡는다(배포 중 겹침 포함).</li>
     * </ul>
     */
    public static final class StoreBudget {

        /** 키 하나에 더해 셀 고정 비용 — 잰 값(41~59B)보다 크게. */
        static final int PER_KEY_OVERHEAD = 64;

        private final long capacity;
        private final LongSupplier nanos;
        private final java.util.PriorityQueue<Hold> live = new java.util.PriorityQueue<>((a, b) -> Long.compare(a.expiresAtNanos - b.expiresAtNanos, 0));
        private long used;
        private long lastWarnNanos = Long.MIN_VALUE;

        /** @param nanos 경과 시간을 재는 단조 증가 시간원(기본 System::nanoTime) — 벽시계를 앞으로 맞춰도 살아 있는 값의 몫이 풀리지 않게 */
        StoreBudget(long capacity, LongSupplier nanos) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("budget must be positive: " + capacity);
            }
            this.capacity = capacity;
            this.nanos = Objects.requireNonNull(nanos, "nanos");
        }

        /** Redis 메모리로 셀 양 — 키 + 값 + 고정 비용 + 값의 1/4(큰 값의 할당 구간 몫). */
        static long charge(int keyBytes, int valueBytes) {
            return keyBytes + valueBytes + PER_KEY_OVERHEAD + valueBytes / 4L;
        }

        /**
         * 지금부터 holdFor 동안 bytes 를 잡는다. 상한을 넘으면 null. 만료 시각은 잡을 때 정한다 — 미확정 상태가 없어(특별한 값으로 표시하지 않는다)
         * 음수 nanoTime 에서 넘침으로 몫이 풀리는 일이 없다. 넣는 값의 Redis 만료가 절대 시각이라, 그 시각 + 시계 차 여유를 holdFor 로 준다.
         */
        synchronized Hold reserve(long bytes, Duration holdFor) {
            return reserve(bytes, holdFor, null, null, -1);
        }

        /** @param key 넣는 키 · generation · generationNumber — 세대가 오르면 옛 세대 몫을 찾아 지우려고 같이 적는다 */
        synchronized Hold reserve(long bytes, Duration holdFor, String key, String generation, long generationNumber) {
            purge();
            if (used + bytes > capacity) {
                return null;
            }
            Hold hold = new Hold(bytes, nanos.getAsLong() + holdFor.toNanos(), key, generation, generationNumber);
            live.add(hold);
            used += bytes;
            return hold;
        }

        /** 보내지 않은 것이 확실하다(차단기가 막음) — 바로 돌려준다. */
        synchronized void release(Hold hold) {
            if (live.remove(hold)) {
                used -= hold.bytes;
            }
        }

        /** 아직 살아 있는 몫 중 그 세대의 옛 번호(current 미만)로 넣은 것. */
        synchronized List<Hold> olderThan(String generation, long current) {
            purge();
            return live.stream().filter(h -> h.key != null && generation.equals(h.generation) && h.generationNumber < current).toList();
        }

        synchronized long used() {
            purge();
            return used;
        }

        private void purge() {
            long now = nanos.getAsLong();
            while (!live.isEmpty() && live.peek().expiresAtNanos - now <= 0) {
                used -= live.poll().bytes;
            }
        }

        /** 잡은 몫 하나. */
        static final class Hold {
            private final long bytes;
            private final long expiresAtNanos;
            private final String key;
            private final String generation;
            private final long generationNumber;

            private Hold(long bytes, long expiresAtNanos, String key, String generation, long generationNumber) {
                this.bytes = bytes;
                this.expiresAtNanos = expiresAtNanos;
                this.key = key;
                this.generation = generation;
                this.generationNumber = generationNumber;
            }

            String key() {
                return key;
            }
        }

        /** 상한을 넘어 넣지 않았다 — 분마다 한 번만 남긴다. */
        synchronized void warnSkipped(String name) {
            long now = nanos.getAsLong();
            if (lastWarnNanos == Long.MIN_VALUE || now - lastWarnNanos >= 60_000_000_000L) {
                lastWarnNanos = now;
                log.warn("읽기 캐시 저장 상한({} bytes)을 넘어 넣지 않았다 — DB 로 답한다 cache={}", capacity, name);
            }
        }
    }
}
