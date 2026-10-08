package com.grandis.nova.member.auth.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.JwtProperties;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 리프레시 행 정리 — 실제 MySQL. 주기 실행은 시험에서 꺼 두고 run() 을 직접 부른다.
 *
 * 공유 DB 라 다른 시험의 행과 섞이지 않게, 정리 범위를 다루는 시험은 <b>먼 과거의 고정 시계</b>로 만든 정리 객체를 쓴다 — 기준 시각(지금 − 500일)
 * 근처에 넣은 이 시험의 행만 대상이 된다. 회전과 함께 도는 시험(재사용 · 회전 · 잠금)만 앱의 정리 빈(실제 시계)을 쓴다.
 */
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

    UUID customerId;
    Instant now;
    Instant base;   // 이 시험의 행을 두는 먼 과거 — 다른 시험의 행은 이보다 훨씬 최근이다

    @BeforeEach
    void setUp() {
        customerId = customer();
        now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        base = now.minus(Duration.ofDays(500)).minusSeconds(ThreadLocalRandom.current().nextInt(86_400));
    }

    /** 이 시험이 남긴 행을 지운다 — 먼 과거 행이 다음 시험의 고정 시계 범위에 걸려 개수 단언을 흔들지 않게. */
    @AfterEach
    void removeOwnRows() {
        jdbc.update("DELETE FROM refresh_tokens WHERE customer_id = ?", (Object) UuidBinary.toBytes(customerId));
    }

    /** 고정 시계가 base + offset 을 가리키는 정리. cutoff 는 그 시각 − 액세스 유효기간이다. */
    private RefreshTokenCleanup cleanupAt(Instant fixedNow, int batchSize, int maxBatches) {
        return new RefreshTokenCleanup(rows, Clock.fixed(fixedNow, ZoneOffset.UTC), new RefreshTokenCleanup.Settings(batchSize, maxBatches), jwt);
    }

    @Test
    @DisplayName("만료된 뒤 액세스 유효기간 + 10분(40분)이 지난 행만 지운다 — 유예 안의 만료 행 · 유효 행은 남는다")
    void deletesOnlyRowsPastTheGrace() {
        Instant fixedNow = base.plus(Duration.ofDays(1));
        UUID longExpired = row(fixedNow.minus(Duration.ofHours(1)));
        UUID justExpired = row(fixedNow.minusSeconds(1));
        UUID valid = row(fixedNow.plus(Duration.ofDays(1)));

        assertThat(cleanupAt(fixedNow, 1000, 1000).run()).isEqualTo(1);

        assertThat(exists(longExpired)).isFalse();
        assertThat(exists(justExpired)).as("유예(액세스 유효기간) 안 — 재사용 탐지에 아직 쓰인다").isTrue();
        assertThat(exists(valid)).isTrue();
    }

    @Test
    @DisplayName("경계는 정확히 만료 + 액세스 유효기간 + 10분이다 — 그 순간 만료분까지 지우고 1초 늦은 것은 남긴다")
    void graceBoundaryIsExact() {
        Instant fixedNow = base.plus(Duration.ofDays(1));
        Instant boundary = fixedNow.minus(jwt.accessTokenValidity()).minus(RefreshTokenCleanup.MARGIN_AFTER_DETECTION);
        UUID atBoundary = row(boundary);
        UUID oneSecondLater = row(boundary.plusSeconds(1));

        assertThat(cleanupAt(fixedNow, 1000, 1000).run()).isEqualTo(1);

        assertThat(exists(atBoundary)).isFalse();
        assertThat(exists(oneSecondLater)).isTrue();
    }

    @Test
    @DisplayName("보존은 만료 뒤 40분이다(액세스 30분 + 여유 10분) — 상수가 아니라 숫자로 못 박는다: 40분 된 행은 지우고 39분 59초 된 행은 남긴다")
    void retentionIsFortyMinutesAfterExpiry() {
        assertThat(jwt.accessTokenValidity()).as("시험 설정의 액세스 유효기간").isEqualTo(Duration.ofMinutes(30));
        Instant fixedNow = base.plus(Duration.ofDays(1));
        UUID fortyMinutes = row(fixedNow.minus(Duration.ofMinutes(40)));
        UUID justUnder = row(fixedNow.minus(Duration.ofMinutes(40)).plusSeconds(1));

        cleanupAt(fixedNow, 1000, 1000).run();

        assertThat(exists(fortyMinutes)).isFalse();
        assertThat(exists(justUnder)).as("회전의 탐지 창(30분) + 10분 안 — 재사용 탐지가 끝나기 전에 지우면 안 된다").isTrue();
    }

    @Test
    @DisplayName("만료 직후 교체된 토큰이 재사용되면 정리 뒤에도 재사용으로 잡힌다 — 행이 유예 동안 남아 체인 폐기 · 액세스 폐기 표식이 선다")
    void reuseIsStillDetectedJustAfterExpiry() {
        String first = RefreshTokens.newToken();
        String second = RefreshTokens.newToken();
        store.save(UUID.randomUUID(), String.valueOf(customerId), first, now.plus(Duration.ofDays(14)));
        assertThat(store.rotate(first, second).status()).isEqualTo(Rotation.Status.ROTATED);
        // 체인이 5초 전에 만료됐다(created_at 도 같이 당겨 ck_refresh_expiry 를 지킨다)
        jdbc.update("UPDATE refresh_tokens SET created_at = ?, expires_at = ? WHERE customer_id = ?",
                utc(now.minus(Duration.ofDays(1))), utc(now.minusSeconds(5)), UuidBinary.toBytes(customerId));

        cleanup.run();

        assertThat(store.rotate(first, RefreshTokens.newToken()).status())
                .as("만료 즉시 지웠다면 NOT_FOUND — 재사용이 조용히 지나간다").isEqualTo(Rotation.Status.REUSED);
    }

    @Test
    @DisplayName("묶음 크기 × 묶음 수가 한 번에 지우는 상한이다 — 남은 것은 다음 실행이 지운다")
    void boundedPerRun() {
        for (int i = 0; i < 5; i++) {
            row(base.plusSeconds(i));
        }
        Instant fixedNow = base.plus(Duration.ofHours(1)).plus(jwt.accessTokenValidity()).plus(RefreshTokenCleanup.MARGIN_AFTER_DETECTION);   // cutoff = base + 1시간 — 이 시험의 다섯 행만

        assertThat(cleanupAt(fixedNow, 2, 2).run()).as("2 × 2").isEqualTo(4);
        assertThat(cleanupAt(fixedNow, 2, 2).run()).as("나머지").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ?", Long.class,
                UuidBinary.toBytes(customerId))).isZero();
    }

    @Test
    @DisplayName("정리 뒤에도 유효한 토큰의 회전은 그대로 된다 — 만료된 체인만 사라졌다")
    void rotationStillWorksAfterCleanup() {
        String raw = RefreshTokens.newToken();
        store.save(UUID.randomUUID(), String.valueOf(customerId), raw, now.plus(Duration.ofDays(14)));
        row(now.minus(Duration.ofDays(1)));

        cleanup.run();

        Rotation rotation = store.rotate(raw, RefreshTokens.newToken());
        assertThat(rotation.status()).isEqualTo(Rotation.Status.ROTATED);
    }

    @Test
    @DisplayName("진행 중인 회전(유효 행 잠금)을 기다리지도 건드리지도 않는다 — 한 문장 DELETE 는 이 잠금을 50초 기다렸다(실측), 그래서 고른 만료 id 만 지운다")
    void doesNotWaitForAnInFlightRotation() throws Exception {
        String raw = RefreshTokens.newToken();
        store.save(UUID.randomUUID(), String.valueOf(customerId), raw, now.plus(Duration.ofDays(14)));
        UUID expired = row(now.minus(Duration.ofDays(2)));
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
        assertThat(store.rotate(raw, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.ROTATED);
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

    private UUID customer() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, kakao_id, display_name, created_at, updated_at)"
                        + " VALUES (?, ?, 'cleanup', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                UuidBinary.toBytes(id), "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 18));
        return id;
    }

    /** 이 회원의 리프레시 행 하나. 만료 시각만 고른다(발급 시각은 그보다 하루 앞 — ck_refresh_expiry). */
    private UUID row(Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO refresh_tokens (id, customer_id, family_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UuidBinary.toBytes(id), UuidBinary.toBytes(customerId), UUID.randomUUID().toString(),
                RefreshTokens.hash(RefreshTokens.newToken()), utc(expiresAt), utc(expiresAt.minus(Duration.ofDays(1))));
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE id = ?", Long.class,
                (Object) UuidBinary.toBytes(id)) == 1;
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
