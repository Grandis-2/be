package com.grandis.nova.waitingroom.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * member 의 JWKS 를 기동 때와 주기마다 뒤에서 받아 둔다. 요청은 받아 둔 키로만 답하고 받기를 기다리지 않는다 —
 * 모르는 kid 는 바로 401 이고 갱신은 뒤에서 한다. 갱신은 {@link #MIN_INTERVAL} 에 한 번까지라 위조 kid 로 member 를 두드리지 못한다.
 */
final class JwkSetCache implements Function<SignedJWT, Flux<JWK>>, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JwkSetCache.class);

    /** 주기 갱신 간격. member 가 새 키를 게시하고 이만큼 지난 뒤 서명을 바꾸면 401 이 나지 않는다. */
    static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);

    /** 갱신 시도 사이 최소 간격. 실패한 시도도 센다. */
    static final Duration MIN_INTERVAL = Duration.ofSeconds(10);

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** 못 받는 동안 받아 둔 키로 버티는 한도. 끝이 없으면 member 를 끊어 뺀 키를 살려 둘 수 있다. */
    static final Duration STALE_LIMIT = Duration.ofHours(1);

    private final Mono<String> fetch;
    private final Clock clock;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final AtomicReference<Instant> lastAttempt = new AtomicReference<>();
    private final AtomicReference<Instant> failingSince = new AtomicReference<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean exhausted = new AtomicBoolean();
    private volatile Disposable periodic;

    JwkSetCache(Mono<String> fetch, Clock clock) {
        this.fetch = fetch;
        this.clock = clock;
    }

    @Override
    public Flux<JWK> apply(SignedJWT jwt) {
        Instant now = clock.instant();
        Snapshot snapshot = current.get();
        if (snapshot == null || !snapshot.usable(now)) {
            refreshInBackground(now);
            return Flux.error(new IllegalStateException("JWKS is not available"));
        }
        String kid = jwt.getHeader().getKeyID();
        if (kid != null && snapshot.keys().getKeyByKeyId(kid) == null) {
            refreshInBackground(now);
        }
        return Flux.fromIterable(snapshot.keys().getKeys());
    }

    /**
     * 한 번이라도 받았는가. readiness 가 본다 — 기동한 태스크가 키 없이 트래픽을 받지 않게 하되,
     * 뒤에 member 가 오래 죽어도 이미 뜬 태스크를 내리지 않는다(그때는 요청이 503 으로 답한다).
     */
    boolean loadedOnce() {
        return current.get() != null;
    }

    /** 기다리지 않는다. 간격 안이면 건너뛴다. */
    void refreshInBackground(Instant now) {
        Instant last = lastAttempt.get();
        if (last != null && now.isBefore(last.plus(MIN_INTERVAL))) {
            return;
        }
        if (lastAttempt.compareAndSet(last, now)) {
            refresh().subscribe(set -> { }, e -> { });
        }
    }

    /** 한 번 받는다. 실패는 failed 가 기록하고 오류로 끝난다. */
    Mono<JWKSet> refresh() {
        return fetch.timeout(TIMEOUT)
                .handle(this::parse)
                .doOnNext(this::stored)
                .doOnError(this::failed);
    }

    @Override
    public void start() {
        periodic = Flux.interval(Duration.ZERO, REFRESH_INTERVAL)
                .concatMap(tick -> {
                    lastAttempt.set(clock.instant());
                    return refresh().onErrorResume(e -> Mono.empty());
                })
                .subscribe();
    }

    @Override
    public void stop() {
        Disposable running = periodic;
        if (running != null) {
            running.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        Disposable running = periodic;
        return running != null && !running.isDisposed();
    }

    /** 짧은 RSA 키는 버린다. 정적 공개키에 거는 하한과 같다. */
    private void parse(String body, SynchronousSink<JWKSet> sink) {
        try {
            List<JWK> all = JWKSet.parse(body).getKeys();
            List<JWK> kept = all.stream()
                    .filter(key -> !(key instanceof RSAKey rsa) || rsa.size() >= JwtDecoderFactory.MIN_RSA_BITS)
                    .toList();
            // 빈 집합으로 덮으면 갖고 있던 키를 잃고 모든 토큰이 401 이 된다 — 실패로 보고 가진 것을 지킨다
            if (kept.isEmpty()) {
                sink.error(new IllegalStateException("JWKS 에 쓸 수 있는 키가 없다"));
                return;
            }
            if (kept.size() < all.size()) {
                log.warn("JWKS 에서 {} 비트보다 짧은 RSA 키 {}개를 버렸다", JwtDecoderFactory.MIN_RSA_BITS,
                        all.size() - kept.size());
            }
            sink.next(new JWKSet(kept));
        } catch (ParseException e) {
            sink.error(new IllegalStateException("JWKS 를 읽지 못했다", e));
        }
    }

    private void stored(JWKSet set) {
        current.set(new Snapshot(set, clock.instant()));
        exhausted.set(false);
        Instant since = failingSince.getAndSet(null);
        if (since != null) {
            log.info("JWKS 를 다시 받았다 — {}초 동안 {}번 실패",
                    Duration.between(since, clock.instant()).toSeconds(), failures.getAndSet(0));
        }
    }

    /** 상태가 바뀔 때만 남긴다. 그 사이 실패는 세기만 하고 회복 로그가 횟수를 싣는다. */
    private void failed(Throwable e) {
        Instant now = clock.instant();
        failures.incrementAndGet();
        Snapshot snapshot = current.get();
        boolean usable = snapshot != null && snapshot.usable(now);
        if (failingSince.compareAndSet(null, now) && usable) {
            log.warn("JWKS 를 받지 못해 {} 에 받은 것으로 버틴다: {}", snapshot.at(), e.toString());
        }
        if (!usable && exhausted.compareAndSet(false, true)) {
            log.error("쓸 JWKS 가 없어 JWKS 키로 서명된 요청이 503 이다: {}", e.toString());
        }
    }

    private record Snapshot(JWKSet keys, Instant at) {

        boolean usable(Instant now) {
            return now.isBefore(at.plus(STALE_LIMIT));
        }
    }
}
