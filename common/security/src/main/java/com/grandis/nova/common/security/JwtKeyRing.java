package com.grandis.nova.common.security;

import io.jsonwebtoken.Header;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import java.security.Key;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 서명 키(있으면)와 검증 공개키 묶음. 토큰 헤더의 `kid` 로 공개키를 고른다(RFC 7515).
 *
 * 공개키 출처는 둘이고 순서대로 본다: (1) 설정 — 개인키에서 계산한 공개키(지금 서명 kid) + `public-keys`(교체 중인 **옛** 키), (2) `jwk-set-uri` 에서 받은 JWKS(캐시).
 * (1) 은 정적이다 — 기동 때 한 번 만든다. (2) 는 **요청 스레드에서 받지 않는다.** 받기는 전부 백그라운드 executor 에서 한다:
 * - 주기 갱신: {@link #PERIODIC_REFRESH}(5분)마다. 키 교체 때 "새 공개키를 먼저 게시하고 5분 이상 기다린 뒤 서명을 바꾸면" 어느 인스턴스도 401 을 내지 않는다.
 * - 모르는 kid: 그 요청은 바로 401(없음)이고, 갱신 한 번을 executor 에 넘긴다. 다음 요청부터 새 kid 가 잡힌다. 사용자 요청 처리 경로에서 외부 호출을 기다리지 않는다는
 *   이 저장소의 원칙을 따른다 — JWKS 서버(member)가 느리거나 죽어도 요청 스레드가 묶이지 않는다.
 *
 * 상한 셋:
 * - 성공한 조회 뒤 10초(MIN_REFRESH_INTERVAL) 안에는 모르는 kid 가 와도 다시 받지 않는다 — 위조 kid 로 발급 서비스에 조회를 퍼붓지 못하게.
 * - 실패한 조회(5xx·타임아웃) 뒤에는 1초(FAILURE_RETRY_AFTER)만 쉬고 다음 모르는 kid 가 다시 받게 한다 — 교체 직후 member 가 잠깐 못 받았다고 10초를 401 로 보내지 않게.
 * - 갱신은 한 번에 하나만 진행한다(refreshInFlight). 진행 중에 온 모르는 kid 요청은 갱신을 또 걸지 않고 401 이다.
 * JWKS 서버가 죽어 있으면 캐시된 키로 계속 검증하고, 캐시도 없으면 그 토큰은 거절된다(500 이 아니라 401).
 *
 * 스프링 빈으로 쓸 때는 {@link #withBackgroundRefresh}: 데몬 스레드 하나가 주기·비동기 갱신을 맡고, 컨텍스트가 닫힐 때 {@link #close()} 가 그 스레드를 멈춘다.
 * 시험은 생성자에 동기 executor(`Runnable::run`)나 작업을 모아 두는 executor 를 넣어 갱신 시점을 손에 쥔다.
 */
public class JwtKeyRing implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyRing.class);
    static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(10);
    static final Duration FAILURE_RETRY_AFTER = Duration.ofSeconds(1);
    /** 검증 서비스의 백그라운드 JWKS 갱신 주기. 키 교체 절차의 "미리 게시" 대기 시간의 근거다. */
    public static final Duration PERIODIC_REFRESH = Duration.ofMinutes(5);

    private final String keyId;
    private final RSAPrivateKey signingKey;
    private final Map<String, RSAPublicKey> staticKeys;
    private final String jwkSetUri;
    private final RestClient rest;
    private final Clock clock;
    private final AtomicReference<Map<String, RSAPublicKey>> fetched = new AtomicReference<>(Map.of());
    private final AtomicReference<Instant> lastRefresh = new AtomicReference<>(Instant.EPOCH);
    private final Executor refresher;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final ScheduledExecutorService ownedScheduler;

    /** 갱신을 어디서 돌릴지 호출자가 정한다. 요청 스레드에서는 절대 돌리지 않는다. */
    public JwtKeyRing(JwtProperties properties, Clock clock, RestClient rest, Executor refresher) {
        this(properties, clock, rest, refresher, null);
    }

    private JwtKeyRing(JwtProperties properties, Clock clock, RestClient rest, Executor refresher, ScheduledExecutorService ownedScheduler) {
        this.clock = clock;
        this.rest = rest;
        this.refresher = refresher;
        this.ownedScheduler = ownedScheduler;
        this.jwkSetUri = properties.jwkSetUri() == null || properties.jwkSetUri().isBlank() ? null : properties.jwkSetUri();
        if (this.jwkSetUri != null && !this.jwkSetUri.startsWith("https://")) {
            // 설정이 jwk-set-allow-http 로 명시한 예외다. 이 경로의 응답을 바꿔치기하면 토큰 위조가 되므로 VPC 안·보안 그룹 제한이 전제이고, 내부 TLS 가 붙으면 없앤다.
            log.warn("jwks over plain http: {} — accepted only because jwt.jwk-set-allow-http=true; restrict the path with security groups and switch to https when internal TLS is available", this.jwkSetUri);
        }
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        if (properties.issues()) {
            this.keyId = properties.keyId();
            this.signingKey = PemKeys.parsePrivate(properties.privateKey());
            keys.put(keyId, PemKeys.publicOf(signingKey));
        } else {
            this.keyId = null;
            this.signingKey = null;
        }
        properties.publicKeys().forEach((kid, pem) -> keys.putIfAbsent(kid, PemKeys.parsePublic(pem)));
        this.staticKeys = Collections.unmodifiableMap(keys);
    }

    /**
     * 운영용: 데몬 스레드 하나로 주기 갱신(즉시 한 번, 그 뒤 5분마다)과 모르는 kid 때의 비동기 갱신을 돌린다.
     * `jwk-set-uri` 가 없으면(발급 서비스·정적 공개키만) 스레드를 만들지 않는다.
     */
    public static JwtKeyRing withBackgroundRefresh(JwtProperties properties, Clock clock, RestClient rest) {
        boolean fetches = properties.jwkSetUri() != null && !properties.jwkSetUri().isBlank();
        if (!fetches) {
            return new JwtKeyRing(properties, clock, rest, Runnable::run, null);
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jwks-refresh");
            t.setDaemon(true);
            return t;
        });
        JwtKeyRing ring = new JwtKeyRing(properties, clock, rest, scheduler, scheduler);
        scheduler.scheduleWithFixedDelay(ring::refreshNow, 0, PERIODIC_REFRESH.toSeconds(), TimeUnit.SECONDS);
        return ring;
    }

    /** JWKS 를 원격에서 받는 검증 서비스인가. */
    public boolean fetchesJwks() {
        return jwkSetUri != null;
    }

    @Override
    public void close() {
        if (ownedScheduler != null) {
            ownedScheduler.shutdownNow();
        }
    }

    public boolean canSign() {
        return signingKey != null;
    }

    public String keyId() {
        return keyId;
    }

    public RSAPrivateKey signingKey() {
        if (signingKey == null) {
            throw new IllegalStateException("this service has no jwt.private-key — it verifies tokens but does not issue them");
        }
        return signingKey;
    }

    /** JWKS 로 게시할 키: 지금 서명 키의 공개키 + 설정의 공개키(교체 중인 이전 키). 원격에서 받은 키는 다시 게시하지 않는다. */
    public Map<String, RSAPublicKey> publishedKeys() {
        return staticKeys;
    }

    public Optional<RSAPublicKey> resolve(String kid) {
        if (kid == null || kid.isBlank()) {
            return Optional.empty();
        }
        RSAPublicKey key = staticKeys.get(kid);
        if (key != null) {
            return Optional.of(key);
        }
        key = fetched.get().get(kid);
        if (key != null) {
            return Optional.of(key);
        }
        if (jwkSetUri != null) {
            // 갱신은 executor 에 넘기고 기다리지 않는다. 캐시를 한 번 더 읽는 건 동기 executor(시험)나 방금 끝난 갱신을 위한 것이고,
            // 운영(비동기)에서는 보통 그대로 '없음' → 이 요청은 401, 다음 요청부터 새 kid 가 잡힌다.
            requestRefresh();
            key = fetched.get().get(kid);
        }
        return Optional.ofNullable(key);
    }

    /**
     * 요청 스레드에서 부른다. 간격 상한 안이거나 갱신이 이미 진행 중이면 아무것도 하지 않고, 아니면 갱신 한 번을 executor 에 넘기고 바로 돌아온다.
     */
    void requestRefresh() {
        if (Duration.between(lastRefresh.get(), clock.instant()).compareTo(MIN_REFRESH_INTERVAL) < 0) {
            return;
        }
        if (!refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            refresher.execute(() -> {
                try {
                    refreshIfAllowed();
                } finally {
                    refreshInFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            // executor 가 닫혔거나(종료 중) 거부했다. 요청은 401 로 끝나고 다음 요청이 다시 건다.
            refreshInFlight.set(false);
            log.warn("jwks refresh could not be scheduled: {}", e.getClass().getSimpleName());
        }
    }

    /** 호출 스레드에서 받는다. 주기 갱신 작업과 시험이 쓴다. 간격 상한(성공 뒤 10초·실패 뒤 1초)은 그대로 적용된다. */
    public boolean refreshNow() {
        return jwkSetUri != null && refreshIfAllowed();
    }

    /** jjwt 파서에 꽂는 키 선택기. kid 가 없거나 모르는 kid 면 InvalidTokenException — 파서 밖에서 401 로 뭉친다. */
    public Locator<Key> locator() {
        return new Locator<>() {
            @Override
            public Key locate(Header header) {
                String kid = header instanceof ProtectedHeader ph ? ph.getKeyId() : null;
                if (kid == null || kid.isBlank()) {
                    throw new InvalidTokenException("missing kid header");
                }
                return resolve(kid).map(k -> (Key) k).orElseThrow(() -> new InvalidTokenException("unknown kid"));
            }
        };
    }

    private boolean refreshIfAllowed() {
        Instant now = clock.instant();
        Instant last = lastRefresh.get();
        if (Duration.between(last, now).compareTo(MIN_REFRESH_INTERVAL) < 0) {
            return false;
        }
        if (!lastRefresh.compareAndSet(last, now)) {
            return false;   // 다른 스레드가 방금 받았다
        }
        try {
            String json = rest.get().uri(jwkSetUri).retrieve().body(String.class);
            JwkSet set = Jwks.setParser().build().parse(json);
            Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
            for (Jwk<?> jwk : set.getKeys()) {
                if (jwk.getId() != null && usableForSignature(jwk) && jwk.toKey() instanceof RSAPublicKey pub) {
                    keys.put(jwk.getId(), pub);
                }
            }
            fetched.set(Collections.unmodifiableMap(keys));
            log.info("jwks refreshed from {}: {} key(s)", jwkSetUri, keys.size());
            return true;
        } catch (RuntimeException e) {
            // 받기에 실패해도 캐시는 유지한다. 예외 종류만 남긴다(URI 는 설정값이라 실어도 된다).
            // 실패는 10초를 다 쉬지 않는다 — 1초 뒤 다음 모르는 kid 요청이 다시 걸게 한다.
            lastRefresh.set(now.minus(MIN_REFRESH_INTERVAL).plus(FAILURE_RETRY_AFTER));
            log.warn("jwks refresh failed from {}: {}", jwkSetUri, e.getClass().getSimpleName());
            return false;
        }
    }

    /** `use` 가 있으면 sig 여야 하고 `alg` 가 있으면 RS256 이어야 한다. 암호화용·다른 알고리즘용 키를 서명 검증에 쓰지 않는다. */
    private static boolean usableForSignature(Jwk<?> jwk) {
        Object use = jwk.get("use");
        Object alg = jwk.get("alg");
        return (use == null || "sig".equals(use)) && (alg == null || JwtTokenProvider.ALG.equals(alg));
    }

    /** JWKS 문서(RFC 7517). 공개 정보만 들어간다: kty·kid·use·alg·n·e. */
    public Map<String, Object> jwks() {
        var keys = new java.util.ArrayList<Map<String, Object>>();
        publishedKeys().forEach((kid, key) -> {
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kty", "RSA");
            jwk.put("kid", kid);
            jwk.put("use", "sig");
            jwk.put("alg", "RS256");
            jwk.put("n", PemKeys.base64UrlUnsigned(key.getModulus()));
            jwk.put("e", PemKeys.base64UrlUnsigned(key.getPublicExponent()));
            keys.add(jwk);
        });
        return Map.of("keys", keys);
    }

    /** JWKS 문서를 정적 공개키로 읽는다(시험·검증 전용 서비스가 파일로 받을 때). */
    public static Map<String, RSAPublicKey> parseJwks(String json) {
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        for (Jwk<?> jwk : Jwks.setParser().build().parse(json).getKeys()) {
            if (jwk.getId() != null && usableForSignature(jwk) && jwk.toKey() instanceof PublicKey pub && pub instanceof RSAPublicKey rsa) {
                keys.put(jwk.getId(), rsa);
            }
        }
        return keys;
    }
}
