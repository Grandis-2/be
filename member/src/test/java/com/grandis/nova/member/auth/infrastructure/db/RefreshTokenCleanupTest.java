package com.grandis.nova.member.auth.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.common.security.JwtProperties;
import com.grandis.nova.member.auth.application.ClientInfo;
import com.grandis.nova.member.auth.application.RefreshTokenStore.Rotation;
import com.grandis.nova.member.auth.application.RefreshTokens;
import com.grandis.nova.member.support.MemberIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 만료 리프레시 행 정리 — 실제 MySQL. 주기 실행은 시험에서 꺼 두고 run() 을 직접 부른다. */
@MemberIntegrationTest
@DisplayName("만료 리프레시 정리")
class RefreshTokenCleanupTest {

    @Autowired RefreshTokenCleanup cleanup;
    @Autowired RefreshTokenRepository rows;
    @Autowired DbRefreshTokenStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;
    @Autowired JwtProperties jwt;

    long customerId;
    Instant now;

    @BeforeEach
    void setUp() {
        customerId = customer();
        now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("만료된 뒤 액세스 유효기간(30분)이 지난 행만 지운다 — 유예 안의 만료 행 · 유효 행은 남는다")
    void deletesOnlyRowsPastTheGrace() {
        long longExpired = row(now.minus(Duration.ofHours(1)));
        long justExpired = row(now.minusSeconds(1));
        long valid = row(now.plus(Duration.ofDays(1)));

        assertThat(cleanup.run()).isGreaterThanOrEqualTo(1);

        assertThat(exists(longExpired)).isFalse();
        assertThat(exists(justExpired)).as("유예(액세스 유효기간) 안 — 재사용 탐지에 아직 쓰인다").isTrue();
        assertThat(exists(valid)).isTrue();
    }

    @Test
    @DisplayName("경계는 정확히 만료 + 액세스 유효기간이다 — 그 순간 만료분까지 지우고 1초 늦은 것은 남긴다")
    void graceBoundaryIsExact() {
        Instant fixedNow = now.plus(Duration.ofDays(400));   // 다른 시험의 행과 섞이지 않는 먼 미래
        Instant boundary = fixedNow.minus(jwt.accessTokenValidity());
        long atBoundary = row(boundary);
        long oneSecondLater = row(boundary.plusSeconds(1));
        RefreshTokenCleanup fixed = new RefreshTokenCleanup(rows, Clock.fixed(fixedNow, ZoneOffset.UTC),
                new RefreshTokenCleanup.Settings(1000, 1000), jwt);

        fixed.run();

        assertThat(exists(atBoundary)).isFalse();
        assertThat(exists(oneSecondLater)).isTrue();
        jdbc.update("DELETE FROM refresh_tokens WHERE id = ?", oneSecondLater);   // 먼 미래 행을 남기지 않는다
    }

    @Test
    @DisplayName("만료 직후 교체된 토큰이 재사용되면 정리 뒤에도 재사용으로 잡힌다 — 행이 유예 동안 남아 체인 폐기 · 액세스 폐기 표식이 선다")
    void reuseIsStillDetectedJustAfterExpiry() {
        String first = RefreshTokens.newToken();
        String second = RefreshTokens.newToken();
        store.save(UUID.randomUUID(), String.valueOf(customerId), first, now.plus(Duration.ofDays(14)), ClientInfo.UNKNOWN);
        assertThat(store.rotate(first, second, ClientInfo.UNKNOWN).status()).isEqualTo(Rotation.Status.ROTATED);
        // 체인이 5초 전에 만료됐다(created_at 도 같이 당겨 ck_refresh_expiry 를 지킨다)
        jdbc.update("UPDATE refresh_tokens SET created_at = ?, expires_at = ? WHERE customer_id = ?",
                utc(now.minus(Duration.ofDays(1))), utc(now.minusSeconds(5)), customerId);

        cleanup.run();

        assertThat(store.rotate(first, RefreshTokens.newToken(), ClientInfo.UNKNOWN).status())
                .as("만료 즉시 지웠다면 NOT_FOUND — 재사용이 조용히 지나간다").isEqualTo(Rotation.Status.REUSED);
    }

    @Test
    @DisplayName("묶음 크기 × 묶음 수가 한 번에 지우는 상한이다 — 남은 것은 다음 실행이 지운다")
    void boundedPerRun() {
        for (int i = 0; i < 5; i++) {
            row(now.minus(Duration.ofDays(30)).plusSeconds(i));   // 이 시험의 만료 행이 가장 오래돼 먼저 지워진다
        }
        RefreshTokenCleanup small = new RefreshTokenCleanup(rows, clock, new RefreshTokenCleanup.Settings(2, 2), jwt);

        assertThat(small.run()).as("2 × 2").isEqualTo(4);
        assertThat(cleanup.run()).as("나머지").isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ?", Long.class, customerId)).isZero();
    }

    @Test
    @DisplayName("정리 뒤에도 유효한 토큰의 회전은 그대로 된다 — 만료된 체인만 사라졌다")
    void rotationStillWorksAfterCleanup() {
        String raw = RefreshTokens.newToken();
        store.save(UUID.randomUUID(), String.valueOf(customerId), raw, now.plus(Duration.ofDays(14)), ClientInfo.UNKNOWN);
        row(now.minus(Duration.ofDays(1)));

        cleanup.run();

        Rotation rotation = store.rotate(raw, RefreshTokens.newToken(), ClientInfo.UNKNOWN);
        assertThat(rotation.status()).isEqualTo(Rotation.Status.ROTATED);
    }

    @Test
    @DisplayName("진행 중인 회전(유효 행 잠금)을 기다리지도 건드리지도 않는다 — 한 문장 DELETE 는 이 잠금을 50초 기다렸다(실측), 그래서 고른 만료 id 만 지운다")
    void doesNotWaitForAnInFlightRotation() throws Exception {
        String raw = RefreshTokens.newToken();
        store.save(UUID.randomUUID(), String.valueOf(customerId), raw, now.plus(Duration.ofDays(14)), ClientInfo.UNKNOWN);
        long expired = row(now.minus(Duration.ofDays(2)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 회전 도중처럼 유효 행을 X 로 잠그고 붙잡는다
            Future<?> rotation = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(rows.findByTokenHashForUpdate(RefreshTokens.hash(raw))).isPresent();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            cleanup.run();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("잠금을 기다렸다면 innodb_lock_wait_timeout 만큼 걸린다")
                    .isLessThan(Duration.ofSeconds(5));
            assertThat(exists(expired)).isFalse();

            release.countDown();
            rotation.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(store.rotate(raw, RefreshTokens.newToken(), ClientInfo.UNKNOWN).status()).isEqualTo(Rotation.Status.ROTATED);
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

    private long customer() {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var insert = connection.prepareStatement(
                    "INSERT INTO customers (kakao_id, display_name, created_at, updated_at) VALUES (?, 'cleanup', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            insert.setString(1, "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            return insert;
        }, key);
        return key.getKey().longValue();
    }

    /** 이 회원의 리프레시 행 하나. 만료 시각만 고른다(발급 시각은 그보다 하루 앞 — ck_refresh_expiry). */
    private long row(Instant expiresAt) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var insert = connection.prepareStatement("""
                    INSERT INTO refresh_tokens (customer_id, family_id, token_hash, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            insert.setLong(1, customerId);
            insert.setString(2, UUID.randomUUID().toString());
            insert.setBytes(3, RefreshTokens.hash(RefreshTokens.newToken()));
            insert.setObject(4, utc(expiresAt));
            insert.setObject(5, utc(expiresAt.minus(Duration.ofDays(1))));
            return insert;
        }, key);
        return key.getKey().longValue();
    }

    private boolean exists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE id = ?", Long.class, id) == 1;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
