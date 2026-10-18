package com.grandis.nova.member.auth.infrastructure.db;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.member.support.Concurrently;
import com.grandis.nova.member.support.MemberIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.member.MemberApplication;
import com.grandis.nova.member.TestKeys;
import com.grandis.nova.member.auth.application.RefreshTokenStore;
import com.grandis.nova.member.auth.application.RefreshTokenStore.Rotation;
import com.grandis.nova.member.auth.application.RefreshTokens;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원 리프레시의 정본 저장소를 **실제 MySQL** 로 본다. 표 주석의 회전 절차가 그대로 도는지, 그리고 그 절차가
 * 동시 재발급에서도 하나만 통과시키는지를 잰다. 잠금·UNIQUE·외래키는 다른 엔진에서 다르게 동작하므로 여기서만 의미가 있다.
 */
@MemberIntegrationTest
@DisplayName("refresh_tokens — 회전 절차 (실제 MySQL)")
class RefreshTokenDbStoreTest {



    private static final java.time.Duration FOURTEEN_DAYS = java.time.Duration.ofDays(14);

    @Autowired RefreshTokenStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired RefreshTokenRepository rows;
    @Autowired TransactionTemplate transactions;
    @Autowired com.grandis.nova.common.security.JwtProperties jwt;

    private UUID newCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id, kakao_id, display_name, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                UuidBinary.toBytes(id), "k-" + UUID.randomUUID(), "회전시험");
        return id;
    }

    private static Instant nowSeconds() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 세션 하나를 만들고 첫 원문을 돌려준다. */
    private String login(UUID customerId, UUID sessionId) {
        String raw = RefreshTokens.newToken();
        store.replace(sessionId, String.valueOf(customerId), raw, nowSeconds().plus(FOURTEEN_DAYS));
        return raw;
    }

    /**
     * 그 원문의 행이 교체된 시각을 seconds 초 앞으로 당긴다 — 유예 창을 시계 없이 넘긴다. 저장된 값에서 빼는 이유: 자바 시각을 써 넣으면 JVM 시간대로
     * 바뀌어(앱은 UTC 로 저장) 9시간 어긋났다(실측).
     */
    private void shiftRotatedAt(String raw, int seconds) {
        jdbc.update("UPDATE refresh_tokens SET rotated_at = rotated_at - INTERVAL ? SECOND WHERE token_hash = ?",
                seconds, RefreshTokens.hash(raw));
    }

    private UUID idOf(String raw) {
        return UuidBinary.fromBytes(jdbc.queryForObject("SELECT id FROM refresh_tokens WHERE token_hash = ?", byte[].class,
                (Object) RefreshTokens.hash(raw)));
    }

    private UUID successorOf(String raw) {
        byte[] to = jdbc.queryForObject("SELECT rotated_to FROM refresh_tokens WHERE token_hash = ?", byte[].class,
                (Object) RefreshTokens.hash(raw));
        return to == null ? null : UuidBinary.fromBytes(to);
    }

    /** 교체도 폐기도 안 된 행 — 지금 쓸 수 있는 토큰. */
    private List<UUID> liveRows(UUID sessionId) {
        return jdbc.queryForList("SELECT id FROM refresh_tokens WHERE family_id = ? AND rotated_at IS NULL AND revoked_at IS NULL",
                byte[].class, sessionId.toString()).stream().map(UuidBinary::fromBytes).toList();
    }

    /** 남의 잠금 대기(performance_schema.data_lock_waits)는 시험 계정에 권한이 없어 root 로 본다(Testcontainers 는 root 비밀번호가 같다). */
    private static final JdbcTemplate ROOT = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
            com.grandis.nova.member.support.Containers.MYSQL.getJdbcUrl(), "root", com.grandis.nova.member.support.Containers.MYSQL.getPassword()));

    /**
     * refresh_tokens 의 잠금 대기가 실제로 생길 때까지 기다린다 — 고정 sleep 으로는 상대가 대기에 들어갔는지 보장하지 못한다. information_schema.innodb_trx
     * 는 쓰지 않는다 — 0.1초 안에 다시 읽으면 앞 값을 그대로 돌려줘(리뷰 실측) 대기가 없어도 통과하거나 있어도 실패했다.
     */
    private void awaitRefreshLockWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ROOT.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l "
                + "ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA = 'shop' AND l.OBJECT_NAME = 'refresh_tokens'",
                Integer.class) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("refresh_tokens 잠금 대기가 생기지 않았다");
            }
            sleep(20);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release 를 받지 못했다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 시험이 앞으로 옮기는 시계(UTC). 여러 스레드가 읽어 volatile 이다. */
    private static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private List<String> familyRows(UUID sessionId) {
        return jdbc.queryForList(
                "SELECT CONCAT(id, ':', IF(rotated_at IS NULL, '-', 'rotated'), ':', IF(revoked_at IS NULL, '-', 'revoked')) "
                        + "FROM refresh_tokens WHERE family_id = ? ORDER BY id", String.class, sessionId.toString());
    }

    // ────────────────────────────── 발급·식별

    @Test
    @DisplayName("replace: 원문은 어디에도 없고 SHA-256 32바이트만 남는다. 체인 식별자는 세션(sid)이다")
    void replaceStoresOnlyTheHash() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = nowSeconds().plus(FOURTEEN_DAYS);

        String raw = RefreshTokens.newToken();
        store.replace(sessionId, String.valueOf(customerId), raw, expiresAt);

        var row = jdbc.queryForMap("SELECT * FROM refresh_tokens WHERE family_id = ?", sessionId.toString());
        assertThat((byte[]) row.get("token_hash")).hasSize(32).isEqualTo(RefreshTokens.hash(raw));
        assertThat(UuidBinary.fromBytes((byte[]) row.get("customer_id"))).isEqualTo(customerId);
        assertThat(row).as("접속 IP · 브라우저 칸은 없앴다").doesNotContainKeys("client_ip", "user_agent");
        assertThat(row.get("rotated_at")).isNull();
        assertThat(row.get("revoked_at")).isNull();
        assertThat((LocalDateTime) row.get("expires_at")).isEqualTo(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        // 원문이 어느 칸에도 그대로 들어가 있지 않다
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(v -> v.contains(raw));
    }

    @Test
    @DisplayName("replace: 그 회원의 살아 있는 세션을 전부 끊고 끊은 sid 를 돌려준다 — 회전한 체인의 행까지, 다른 회원 · 이미 끊긴 세션은 그대로")
    void replaceRevokesEveryLiveSessionOfTheCustomer() {
        UUID target = newCustomer();
        UUID bystander = newCustomer();
        UUID phone = UUID.randomUUID();
        String phoneRaw = login(target, phone);
        assertThat(store.rotate(phoneRaw, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.ROTATED);
        String tabletRaw = expiring(target, nowSeconds().plus(FOURTEEN_DAYS));   // 두 번째 살아 있는 세션
        UUID tablet = store.find(tabletRaw).orElseThrow().sessionId();
        UUID loggedOut = UUID.randomUUID();
        jdbc.update("INSERT INTO refresh_tokens(id, customer_id, family_id, token_hash, expires_at, created_at, revoked_at) VALUES (?,?,?,?,?,?,?)",
                UuidBinary.toBytes(UUID.randomUUID()), UuidBinary.toBytes(target), loggedOut.toString(), RefreshTokens.hash(RefreshTokens.newToken()),
                LocalDateTime.ofInstant(nowSeconds().plus(FOURTEEN_DAYS), ZoneOffset.UTC), LocalDateTime.ofInstant(nowSeconds(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(nowSeconds(), ZoneOffset.UTC));
        login(bystander, UUID.randomUUID());

        UUID laptop = UUID.randomUUID();
        List<UUID> replaced = store.replace(laptop, String.valueOf(target), RefreshTokens.newToken(), nowSeconds().plus(FOURTEEN_DAYS));

        assertThat(replaced).containsExactlyInAnyOrder(phone, tablet);
        assertThat(jdbc.queryForList("SELECT DISTINCT family_id FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL",
                String.class, (Object) UuidBinary.toBytes(target))).containsExactly(laptop.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL",
                Integer.class, (Object) UuidBinary.toBytes(bystander))).isEqualTo(1);
    }

    @Test
    @DisplayName("replace: 막 만료된 세션(탐지 창 30분 안)은 끊고, 창이 지난 세션은 건드리지 않는다 — 그 체인이 낸 액세스가 아직 살 수 있는가")
    void replaceHonoursTheDetectionWindow() {
        UUID customer = newCustomer();
        String justExpired = expiring(customer, nowSeconds().minus(java.time.Duration.ofMinutes(10)));
        String longExpired = expiring(customer, nowSeconds().minus(java.time.Duration.ofMinutes(40)));
        UUID justExpiredSession = store.find(justExpired).orElseThrow().sessionId();

        List<UUID> replaced = store.replace(UUID.randomUUID(), String.valueOf(customer), RefreshTokens.newToken(), nowSeconds().plus(FOURTEEN_DAYS));

        assertThat(replaced).containsExactly(justExpiredSession);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?", LocalDateTime.class,
                (Object) RefreshTokens.hash(longExpired))).as("창이 지난 행").isNull();
    }

    @Test
    @DisplayName("find: 원문으로 주인과 세션을 찾는다. 모르는 원문은 없음")
    void findResolvesTheOwner() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String raw = login(customerId, sessionId);

        assertThat(store.find(raw)).hasValueSatisfying(session -> {
            assertThat(session.sessionId()).isEqualTo(sessionId);
            assertThat(session.subject()).isEqualTo(String.valueOf(customerId));
        });
        assertThat(store.find(RefreshTokens.newToken())).isEmpty();
    }

    // ────────────────────────────── 회전

    @Test
    @DisplayName("rotate 정상: 옛 행에 교체 시각이 찍히고 같은 체인에 새 행이 생긴다. 절대 만료는 세 번 회전해도 그대로다")
    void rotateAddsARowAndKeepsAbsoluteExpiry() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String raw = login(customerId, sessionId);
        LocalDateTime firstExpiry = jdbc.queryForObject(
                "SELECT expires_at FROM refresh_tokens WHERE family_id = ?", LocalDateTime.class, sessionId.toString());

        for (int i = 0; i < 3; i++) {
            String next = RefreshTokens.newToken();
            Rotation rotation = store.rotate(raw, next);
            assertThat(rotation.status()).isEqualTo(Rotation.Status.ROTATED);
            assertThat(rotation.sessionId()).isEqualTo(sessionId);
            assertThat(rotation.subject()).isEqualTo(String.valueOf(customerId));
            raw = next;
        }

        assertThat(familyRows(sessionId)).hasSize(4);   // 첫 발급 + 회전 3
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = ? AND rotated_at IS NOT NULL",
                Integer.class, sessionId.toString())).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT DISTINCT expires_at FROM refresh_tokens WHERE family_id = ?",
                LocalDateTime.class, sessionId.toString())).containsExactly(firstExpiry);
        assertThat(store.find(raw)).isPresent();
    }

    @Test
    @DisplayName("rotate 재사용: 교체 뒤 유예(30초)가 지나 같은 원문이 다시 오면 REUSED 만 돌려주고 체인은 건드리지 않는다 — 끊는 것은 호출자의 세션 폐기다")
    void reuseOnlyDecidesAndSessionRevokeEndsTheChain() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        String second = RefreshTokens.newToken();
        assertThat(store.rotate(first, second).status()).isEqualTo(Rotation.Status.ROTATED);
        shiftRotatedAt(first, 30);

        Rotation reuse = store.rotate(first, RefreshTokens.newToken());

        assertThat(reuse.status()).isEqualTo(Rotation.Status.REUSED);
        assertThat(reuse.sessionId()).isEqualTo(sessionId);
        assertThat(liveRows(sessionId)).as("판정만 — 이 트랜잭션은 체인을 잠그지도 폐기하지도 않는다").containsExactly(idOf(second));
        // 호출자(TokenService)의 세션 폐기 뒤에는 살아남은 토큰이 없다 — 탈취자도 원 소유자도 더 못 쓴다
        store.revokeSession(sessionId);
        assertThat(store.rotate(second, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.REVOKED);
    }

    // ────────────────────────────── 회전 유예

    @Test
    @DisplayName("유예: 교체 뒤 30초 안에 같은 옛 원문이 오고 다음 토큰이 아직 안 쓰였으면 다시 회전한다 — 안 쓰인 다음 토큰은 폐기, 체인은 산다")
    void rotatesAgainWithinGraceWhenSuccessorIsUnused() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        String lost = RefreshTokens.newToken();                 // 브라우저가 응답을 버려 저장되지 못한 쿠키
        assertThat(store.rotate(first, lost).status()).isEqualTo(Rotation.Status.ROTATED);
        assertThat(successorOf(first)).isEqualTo(idOf(lost));

        String retried = RefreshTokens.newToken();
        Rotation again = store.rotate(first, retried);

        assertThat(again.status()).isEqualTo(Rotation.Status.ROTATED);
        assertThat(again.sessionId()).isEqualTo(sessionId);
        assertThat(successorOf(first)).as("옛 행은 새 토큰을 가리킨다").isEqualTo(idOf(retried));
        assertThat(liveRows(sessionId)).as("살아 있는(교체 · 폐기 안 된) 토큰은 늘 하나").containsExactly(idOf(retried));
        assertThat(store.rotate(retried, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.ROTATED);
    }

    @Test
    @DisplayName("유예가 버린 다음 토큰이 다시 오면 재사용이다 — 탈취자가 옛 원문으로 유예 회전을 먼저 해도, 원 소유자가 자기 토큰을 내면 체인 전체가 끊긴다")
    void abandonedSuccessorComingBackIsReuse() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        String owners = RefreshTokens.newToken();
        assertThat(store.rotate(first, owners).status()).isEqualTo(Rotation.Status.ROTATED);        // 소유자는 새 쿠키를 받았다
        String thiefs = RefreshTokens.newToken();
        assertThat(store.rotate(first, thiefs).status()).isEqualTo(Rotation.Status.ROTATED);        // 훔친 옛 원문으로 유예 회전

        Rotation owner = store.rotate(owners, RefreshTokens.newToken());

        assertThat(owner.status()).isEqualTo(Rotation.Status.REUSED);
        assertThat(owner.sessionId()).isEqualTo(sessionId);
        store.revokeSession(sessionId);                                                          // 호출자가 하는 세션 폐기
        assertThat(store.rotate(thiefs, RefreshTokens.newToken()).status()).as("탈취 체인도 죽는다").isEqualTo(Rotation.Status.REVOKED);
    }

    @Test
    @DisplayName("버려진 토큰의 재사용과 로그아웃이 동시에 와도 교착(1213) · 예외 없이 끝나고 체인은 죽는다 — 회전은 판정만 하고 체인을 잠그지 않는다(20회)")
    void abandonedReuseAndLogoutDoNotDeadlock() throws Exception {
        for (int round = 0; round < 20; round++) {
            UUID customerId = newCustomer();
            UUID sessionId = UUID.randomUUID();
            String first = login(customerId, sessionId);
            String abandoned = RefreshTokens.newToken();
            store.rotate(first, abandoned);
            store.rotate(first, RefreshTokens.newToken());

            List<Concurrently.Outcome<Object>> results = Concurrently.run(2, i -> i == 0
                    ? () -> store.rotate(abandoned, RefreshTokens.newToken()).status()
                    : () -> {
                        store.revokeSession(sessionId);
                        return "logout";
                    });

            assertThat(results).as("round %d", round).allSatisfy(r -> assertThat(r.error()).isNull());
            assertThat(results.get(0).value()).isIn(Rotation.Status.REUSED, Rotation.Status.REVOKED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NULL",
                    Integer.class, sessionId.toString())).isZero();
        }
    }

    @Test
    @DisplayName("탈취 쪽 유예 회전이 커밋 전인 사이 원 소유자가 자기 토큰을 내도 재사용이다 — 그 커밋을 기다렸다가 폐기된 행 · 산 체인을 보고 잡는다")
    void ownerArrivingDuringThiefsGraceRotationIsReuse() throws Exception {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        String owners = RefreshTokens.newToken();
        store.rotate(first, owners);
        CountDownLatch thiefInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Rotation.Status> thief = pool.submit(() -> transactions.execute(status -> {
                Rotation.Status s = store.rotate(first, RefreshTokens.newToken()).status();   // 소유자 토큰을 폐기하고 아직 커밋 전
                thiefInside.countDown();
                await(release);
                return s;
            }));
            assertThat(thiefInside.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Rotation> owner = pool.submit(() -> store.rotate(owners, RefreshTokens.newToken()));
            awaitRefreshLockWait();                                   // 소유자가 탈취 쪽 커밋을 실제로 기다린다
            release.countDown();

            assertThat(thief.get(10, TimeUnit.SECONDS)).isEqualTo(Rotation.Status.ROTATED);
            assertThat(owner.get(20, TimeUnit.SECONDS).status()).isEqualTo(Rotation.Status.REUSED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("재사용 탐지는 로그아웃 · 버려진 토큰 재제시와 동시에 와도 교착 · 예외 없이 끝난다 — 판정만 하고 체인을 잠그지 않는다(조합당 20회)")
    void reuseDetectionDoesNotDeadlockWithOtherRevocations() throws Exception {
        for (String partner : new String[] {"logout", "abandoned"}) {
            for (int round = 0; round < 20; round++) {
                UUID customerId = newCustomer();
                UUID sessionId = UUID.randomUUID();
                String first = login(customerId, sessionId);
                String abandoned = RefreshTokens.newToken();
                store.rotate(first, abandoned);
                store.rotate(first, RefreshTokens.newToken());
                shiftRotatedAt(first, 30);                                  // first 는 유예 밖 — 재사용 탐지

                List<Concurrently.Outcome<Object>> results = Concurrently.run(2, i -> i == 0
                        ? () -> store.rotate(first, RefreshTokens.newToken()).status()
                        : "logout".equals(partner)
                                ? () -> {
                                    store.revokeSession(sessionId);
                                    return "logout";
                                }
                                : () -> store.rotate(abandoned, RefreshTokens.newToken()).status());

                assertThat(results).as("%s round %d", partner, round).allSatisfy(r -> assertThat(r.error()).isNull());
            }
        }
    }

    @Test
    @DisplayName("버려진 토큰이라도 체인이 탐지 창(만료 + 액세스 유효기간)을 지났으면 체인을 건드리지 않고 거절(REVOKED) — 창 안이면 재사용")
    void abandonedReuseHonoursTheDetectionWindow() {
        java.time.Duration window = jwt.accessTokenValidity();
        for (boolean inside : new boolean[] {true, false}) {
            UUID customerId = newCustomer();
            UUID sessionId = UUID.randomUUID();
            String first = login(customerId, sessionId);
            String abandoned = RefreshTokens.newToken();
            store.rotate(first, abandoned);
            store.rotate(first, RefreshTokens.newToken());
            pullChainExpiry(customerId, inside ? window.minusSeconds(60) : window.plusSeconds(5));

            assertThat(store.rotate(abandoned, RefreshTokens.newToken()).status()).as(inside ? "창 안" : "창 밖")
                    .isEqualTo(inside ? Rotation.Status.REUSED : Rotation.Status.REVOKED);
        }
    }

    @Test
    @DisplayName("유예 안이어도 다음 토큰이 이미 쓰였으면(그 뒤로 회전했으면) 재사용이다. 받아 주는 건 바로 앞 토큰뿐이다")
    void reuseWithinGraceWhenSuccessorWasUsed() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        String second = RefreshTokens.newToken();
        assertThat(store.rotate(first, second).status()).isEqualTo(Rotation.Status.ROTATED);
        assertThat(store.rotate(second, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.ROTATED);

        assertThat(store.rotate(first, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.REUSED);
        assertThat(liveRows(sessionId)).as("다시 회전하지 않았다 — 새 행 없음").hasSize(1);
    }

    @Test
    @DisplayName("유예 경계: 교체 뒤 28초면 다시 회전, 30초면 재사용 — 초 단위 시각이라 시험 도중 한 초가 넘어가도 판정이 같은 쪽에 남는 값")
    void graceBoundary() {
        UUID customerId = newCustomer();
        UUID inside = UUID.randomUUID();
        String a = login(customerId, inside);
        store.rotate(a, RefreshTokens.newToken());
        shiftRotatedAt(a, 28);
        assertThat(store.rotate(a, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.ROTATED);

        UUID outside = UUID.randomUUID();
        String b = login(newCustomer(), outside);
        store.rotate(b, RefreshTokens.newToken());
        shiftRotatedAt(b, 30);
        assertThat(store.rotate(b, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.REUSED);
    }

    @Test
    @DisplayName("유예 창은 처음 교체 시각부터다 — 유예 안에서 다시 회전해도 늘지 않아, 처음 교체 뒤 30초가 지나면 같은 옛 원문은 재사용이다")
    void graceWindowDoesNotSlide() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        store.rotate(first, RefreshTokens.newToken());
        shiftRotatedAt(first, 20);
        assertThat(store.rotate(first, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.ROTATED);
        shiftRotatedAt(first, 10);                                // 처음 교체 뒤 30초

        assertThat(store.rotate(first, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.REUSED);
    }

    @Test
    @DisplayName("유예 안이어도 절대 만료가 지난 체인은 다시 회전하지 않는다 — 재사용이고 500(만료가 발급보다 앞선 행) 없이 끝난다")
    void expiredChainIsNotRotatedAgainWithinGrace() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        store.rotate(first, RefreshTokens.newToken());
        // 체인을 10초 앞으로 옮기고 만료를 발급 5초 뒤로 — 교체는 10초 전(유예 안), 만료는 5초 전
        jdbc.update("UPDATE refresh_tokens SET created_at = created_at - INTERVAL 10 SECOND, rotated_at = rotated_at - INTERVAL 10 SECOND, "
                + "expires_at = created_at + INTERVAL 5 SECOND WHERE family_id = ?", sessionId.toString());

        assertThat(store.rotate(first, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.REUSED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = ?", Integer.class, sessionId.toString()))
                .as("새 행을 만들지 않았다").isEqualTo(2);
    }

    @Test
    @DisplayName("이 칸이 생기기 전에 교체된 행(rotated_to 가 비었음)은 유예 없이 재사용이다 — 배포 직후의 옛 행")
    void legacyRotatedRowHasNoGrace() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        store.rotate(first, RefreshTokens.newToken());
        jdbc.update("UPDATE refresh_tokens SET rotated_to = NULL WHERE token_hash = ?", (Object) RefreshTokens.hash(first));

        assertThat(store.rotate(first, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.REUSED);
    }

    @Test
    @DisplayName("다음 토큰이 회전 중(행 잠금, 커밋 전)이면 유예 요청은 그 커밋을 기다렸다가 쓰인 것을 보고 재사용이다 — 살아 있는 토큰이 둘이 되지 않는다")
    void graceWaitsForSuccessorRotationInFlight() throws Exception {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        String second = RefreshTokens.newToken();
        store.rotate(first, second);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM refresh_tokens WHERE token_hash = ? FOR UPDATE", RefreshTokens.hash(second));
                locked.countDown();
                await(release);
                jdbc.update("UPDATE refresh_tokens SET rotated_at = created_at WHERE token_hash = ?", (Object) RefreshTokens.hash(second));
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Rotation> retried = pool.submit(() -> store.rotate(first, RefreshTokens.newToken()));
            awaitRefreshLockWait();                                   // 유예 요청이 다음 토큰의 잠금을 실제로 기다린다
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(retried.get(20, TimeUnit.SECONDS).status()).isEqualTo(Rotation.Status.REUSED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("다음 토큰의 잠금을 기다리는 동안 유예가 끝나면 재사용이다 — 판정 시각은 잠근 뒤에 읽는다(교체 28초 뒤 도착, 기다리는 동안 시계를 3초 옮김)")
    void graceEndingWhileWaitingForTheLockIsReuse() throws Exception {
        // 실제 경과 시간에 기대지 않게 이 시험만 조종하는 시계를 쓴다 — 잠금에 닿기 전에 실행이 늦어도 판정이 같다
        MovableClock clock = new MovableClock(nowSeconds());
        DbRefreshTokenStore clocked = new DbRefreshTokenStore(rows, clock, jwt);
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        String second = RefreshTokens.newToken();
        transactions.executeWithoutResult(status -> clocked.rotate(first, second));      // 교체 시각 = 시계
        clock.advance(28);                                                                // 유예 안에 도착
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM refresh_tokens WHERE token_hash = ? FOR UPDATE", RefreshTokens.hash(second));
                locked.countDown();
                await(release);                                       // 다음 토큰을 쓰지는 않는다 — 잠금만 쥔다
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Rotation> retried = pool.submit(() -> transactions.execute(status -> clocked.rotate(first, RefreshTokens.newToken())));
            awaitRefreshLockWait();
            clock.advance(3);                                         // 기다리는 동안 처음 교체 뒤 31초가 된다
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(retried.get(20, TimeUnit.SECONDS).status()).isEqualTo(Rotation.Status.REUSED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("원문 행의 잠금을 기다리는 동안 만료가 지나면 만료다 — 판정 시각은 원문 잠금 뒤에 읽는다(만료 2초 앞 도착, 기다리는 동안 시계를 3초 옮김)")
    void expiryPassingWhileWaitingForTheTokenLockIsExpired() throws Exception {
        MovableClock clock = new MovableClock(nowSeconds());
        DbRefreshTokenStore clocked = new DbRefreshTokenStore(rows, clock, jwt);
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String token = login(customerId, sessionId);
        jdbc.update("UPDATE refresh_tokens SET created_at = ? - INTERVAL 1 DAY, expires_at = ? + INTERVAL 2 SECOND WHERE family_id = ?",
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), sessionId.toString());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM refresh_tokens WHERE token_hash = ? FOR UPDATE", RefreshTokens.hash(token));
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Rotation> rotation = pool.submit(() -> transactions.execute(status -> clocked.rotate(token, RefreshTokens.newToken())));
            awaitRefreshLockWait();
            clock.advance(3);                                         // 기다리는 동안 만료를 넘긴다
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(rotation.get(20, TimeUnit.SECONDS).status()).isEqualTo(Rotation.Status.EXPIRED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("rotate: 모르는 원문 · 폐기된 토큰 · 만료된 토큰은 각각 다른 사유로 거절된다")
    void rejectionsAreDistinguished() {
        UUID customerId = newCustomer();
        assertThat(store.rotate(RefreshTokens.newToken(), RefreshTokens.newToken()).status())
                .isEqualTo(Rotation.Status.NOT_FOUND);

        UUID revokedSession = UUID.randomUUID();
        String revoked = login(customerId, revokedSession);
        store.revokeSession(revokedSession);
        assertThat(store.rotate(revoked, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.REVOKED);

        // 만료된 행은 직접 넣는다. ck_refresh_expiry 가 생성 < 만료를 강제하므로 생성 시각도 과거로 둔다.
        UUID expiredSession = UUID.randomUUID();
        String expired = RefreshTokens.newToken();
        Instant now = nowSeconds();
        jdbc.update("INSERT INTO refresh_tokens(id, customer_id, family_id, token_hash, expires_at, created_at) VALUES (?,?,?,?,?,?)",
                UuidBinary.toBytes(UUID.randomUUID()), UuidBinary.toBytes(customerId), expiredSession.toString(), RefreshTokens.hash(expired),
                LocalDateTime.ofInstant(now.minus(1, ChronoUnit.HOURS), ZoneOffset.UTC),
                LocalDateTime.ofInstant(now.minus(15, ChronoUnit.DAYS), ZoneOffset.UTC));
        assertThat(store.rotate(expired, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.EXPIRED);
    }

    @Test
    @DisplayName("만료 경계: 만료 시각과 같은 초는 이미 만료다. 한 초 앞이면 회전한다")
    void expiryBoundaryIsInclusive() {
        // 만료 판정은 `!expiresAt.isAfter(now)` 다. 경계인 `expiresAt == now` 를 안 태우면
        // 부등호를 한 칸 옮긴 구현도 통과한다 — 액세스 토큰의 `nbf == iat` 를 못 박은 것과 같은 이유다.
        // 저장소의 시계를 고정해 그 순간을 정확히 만든다. 실제 시각으로는 초가 넘어가 경계를 못 맞춘다.
        UUID customerId = newCustomer();
        Instant expiry = nowSeconds().plus(1, ChronoUnit.HOURS);
        String oneSecondEarly = expiring(customerId, expiry);
        String atBoundary = expiring(customerId, expiry);

        assertThat(rotateAt(expiry.minusSeconds(1), oneSecondEarly).status()).isEqualTo(Rotation.Status.ROTATED);
        assertThat(rotateAt(expiry, atBoundary).status()).isEqualTo(Rotation.Status.EXPIRED);
    }

    /** 만료 시각을 지정한 행 하나. ck_refresh_expiry 가 생성 < 만료를 강제하므로 생성은 하루 전으로 둔다. */
    @Test
    @DisplayName("탐지 창(만료 + 액세스 유효기간)이 지난 교체 토큰이 다시 오면 체인을 건드리지 않고 만료로 거절한다")
    void reuseAfterDetectionWindowIsExpiredWithoutTouchingTheChain() {
        UUID customerId = newCustomer();
        String first = login(customerId, UUID.randomUUID());
        assertThat(store.rotate(first, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.ROTATED);
        pullChainExpiry(customerId, jwt.accessTokenValidity().plusMinutes(1));

        assertThat(store.rotate(first, RefreshTokens.newToken()).status()).isEqualTo(Rotation.Status.EXPIRED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NOT NULL", Long.class, UuidBinary.toBytes(customerId)))
                .as("체인 폐기 없음").isZero();
    }

    @Test
    @DisplayName("탐지 창이 지난 재사용은 체인의 다른 행 잠금을 기다리지 않는다 — 정리가 그 행을 지우는 중이어도 교착 없이 만료로 끝난다")
    void pastWindowReuseDoesNotWaitOnChainLocks() throws Exception {
        UUID customerId = newCustomer();
        String first = login(customerId, UUID.randomUUID());
        String second = RefreshTokens.newToken();
        assertThat(store.rotate(first, second).status()).isEqualTo(Rotation.Status.ROTATED);
        pullChainExpiry(customerId, jwt.accessTokenValidity().plusMinutes(1));

        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // 정리가 같은 체인의 다른 행(second)을 지우는 중인 것처럼 그 행을 X 로 잠그고 붙잡는다
            java.util.concurrent.Future<?> cleanup = pool.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(rows.findByTokenHashForUpdate(RefreshTokens.hash(second))).isPresent();
                locked.countDown();
                try {
                    release.await(60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            Rotation rotation = store.rotate(first, RefreshTokens.newToken());
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .as("체인 폐기를 했다면 잠긴 행을 기다린다(innodb_lock_wait_timeout)").isLessThan(java.time.Duration.ofSeconds(5));
            assertThat(rotation.status()).isEqualTo(Rotation.Status.EXPIRED);

            release.countDown();
            cleanup.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("탐지 창 경계는 만료 + 30분이다 — 그 순간의 교체 토큰은 만료, 1초 전이면 아직 재사용으로 체인을 끊는다")
    void detectionWindowBoundary() {
        assertThat(jwt.accessTokenValidity()).isEqualTo(java.time.Duration.ofMinutes(30));
        UUID customerId = newCustomer();
        Instant at = nowSeconds();
        String atBoundary = rotatedExpiring(customerId, at.minus(java.time.Duration.ofMinutes(30)));
        String oneSecondInside = rotatedExpiring(customerId, at.minus(java.time.Duration.ofMinutes(30)).plusSeconds(1));

        assertThat(rotateAt(at, atBoundary).status()).isEqualTo(Rotation.Status.EXPIRED);
        assertThat(rotateAt(at, oneSecondInside).status()).isEqualTo(Rotation.Status.REUSED);
    }

    @Test
    @DisplayName("탐지 창이 지난 체인의 로그아웃은 그 체인 행의 잠금을 기다리지 않는다 — 정리가 지우는 중이어도 바로 끝난다. 창 안이면 폐기된다")
    void logoutOfPastWindowChainDoesNotWait() throws Exception {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        String second = RefreshTokens.newToken();
        assertThat(store.rotate(first, second).status()).isEqualTo(Rotation.Status.ROTATED);
        pullChainExpiry(customerId, java.time.Duration.ofMinutes(41));

        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> cleanup = pool.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(rows.findByTokenHashForUpdate(RefreshTokens.hash(second))).isPresent();
                locked.countDown();
                try {
                    release.await(60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            store.revokeSession(sessionId);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .as("창 밖 행까지 폐기하려 했다면 잠긴 행을 기다린다").isLessThan(java.time.Duration.ofSeconds(5));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NOT NULL", Long.class, UuidBinary.toBytes(customerId)))
                    .as("창이 지난 체인은 폐기하지 않는다 — 잠금을 우회해 UPDATE 한 것도 아니다").isZero();

            release.countDown();
            cleanup.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // 대조군: 창 안의 체인은 로그아웃이 폐기한다
        UUID other = newCustomer();
        UUID live = UUID.randomUUID();
        login(other, live);
        store.revokeSession(live);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL", Long.class, UuidBinary.toBytes(other))).isZero();
    }

    /** 이미 교체된(rotated_at 이 있는) 행 하나. 만료 시각을 지정한다. */
    private String rotatedExpiring(UUID customerId, Instant expiresAt) {
        String raw = expiring(customerId, expiresAt);
        jdbc.update("UPDATE refresh_tokens SET rotated_at = created_at WHERE token_hash = ?", RefreshTokens.hash(raw));
        return raw;
    }

    /** 이 회원의 모든 행을 "지금 − ago" 에 만료된 것으로 당긴다(발급 시각도 같이 — ck_refresh_expiry). */
    private void pullChainExpiry(UUID customerId, java.time.Duration ago) {
        Instant expiresAt = nowSeconds().minus(ago);
        jdbc.update("UPDATE refresh_tokens SET created_at = ?, expires_at = ? WHERE customer_id = ?",
                LocalDateTime.ofInstant(expiresAt.minus(1, ChronoUnit.DAYS), ZoneOffset.UTC),
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC), UuidBinary.toBytes(customerId));
    }

    private String expiring(UUID customerId, Instant expiresAt) {
        String raw = RefreshTokens.newToken();
        jdbc.update("INSERT INTO refresh_tokens(id, customer_id, family_id, token_hash, expires_at, created_at) VALUES (?,?,?,?,?,?)",
                UuidBinary.toBytes(UUID.randomUUID()), UuidBinary.toBytes(customerId), UUID.randomUUID().toString(), RefreshTokens.hash(raw),
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                LocalDateTime.ofInstant(expiresAt.minus(1, ChronoUnit.DAYS), ZoneOffset.UTC));
        return raw;
    }

    /** 시계를 고정한 저장소로 회전한다. 직접 만든 객체라 @Transactional 이 안 붙으므로 트랜잭션을 밖에서 연다. */
    private Rotation rotateAt(Instant at, String presented) {
        DbRefreshTokenStore fixed = new DbRefreshTokenStore(rows, Clock.fixed(at, ZoneOffset.UTC), jwt);
        return transactions.execute(status -> fixed.rotate(presented, RefreshTokens.newToken()));
    }

    @Test
    @DisplayName("같은 원문으로 8개가 동시에 오면(새로고침 연타) 행 잠금이 줄 세우고 전부 회전한다 — 앞 것이 만든 다음 토큰은 매번 폐기돼 살아 있는 토큰은 하나")
    void concurrentRotationLeavesExactlyOneLiveToken() throws Exception {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String raw = login(customerId, sessionId);
        int n = 8;
        List<Concurrently.Outcome<Rotation>> results =
                Concurrently.run(n, i -> () -> store.rotate(raw, RefreshTokens.newToken()));

        assertThat(results).allSatisfy(r -> assertThat(r.error()).isNull());
        assertThat(results.stream().map(r -> r.value().status()).toList()).containsOnly(Rotation.Status.ROTATED);
        assertThat(familyRows(sessionId)).hasSize(1 + n);   // 첫 발급 + 회전 8
        assertThat(liveRows(sessionId)).as("살아 있는 토큰은 마지막 회전 하나 — 옛 행이 가리키는 것").containsExactly(successorOf(raw));
    }

    // ────────────────────────────── 폐기

    @Test
    @DisplayName("revokeSession: 체인 전체를 끊는다. 이미 끊긴 행의 시각은 덮지 않는다")
    void revokeSessionClosesTheChain() {
        UUID customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String raw = login(customerId, sessionId);
        store.rotate(raw, RefreshTokens.newToken());

        store.revokeSession(sessionId);
        LocalDateTime firstRevokedAt = jdbc.queryForObject(
                "SELECT MIN(revoked_at) FROM refresh_tokens WHERE family_id = ?", LocalDateTime.class, sessionId.toString());
        store.revokeSession(sessionId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NULL",
                Integer.class, sessionId.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT MIN(revoked_at) FROM refresh_tokens WHERE family_id = ?",
                LocalDateTime.class, sessionId.toString())).isEqualTo(firstRevokedAt);
    }

    @Test
    @DisplayName("revokeAllOf(제재): 그 회원의 살아 있는 리프레시를 전부 끊는다. 다른 회원은 건드리지 않는다")
    void revokeAllOfClosesEverySessionOfOneCustomer() {
        UUID target = newCustomer();
        UUID bystander = newCustomer();
        UUID phone = UUID.randomUUID();
        login(target, phone);
        expiring(target, nowSeconds().plus(FOURTEEN_DAYS));   // 두 번째 살아 있는 세션 — 로그인으로 만들면 앞 세션이 끊긴다
        UUID other = UUID.randomUUID();
        login(bystander, other);

        store.revokeAllOf(String.valueOf(target));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL",
                Integer.class, UuidBinary.toBytes(target))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL",
                Integer.class, UuidBinary.toBytes(bystander))).isEqualTo(1);
    }

    @Test
    @DisplayName("revokeAllOf: 탐지 창이 지난 행은 건드리지도 기다리지도 않는다 — 정리가 그 행을 잡고 있어도 바로 끝나고, 창 안의 행은 폐기된다")
    void revokeAllOfSkipsPastWindowRowsWithoutWaiting() throws Exception {
        UUID customerId = newCustomer();
        login(customerId, UUID.randomUUID());   // 창 안(살아 있음)
        String justExpired = expiring(customerId, nowSeconds().minus(java.time.Duration.ofMinutes(10)));   // 만료됐지만 창(30분) 안 — 폐기 대상
        String old = expiring(customerId, nowSeconds().minus(java.time.Duration.ofMinutes(41)));   // 창 밖(정리 대상)

        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> cleanup = pool.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(rows.findByTokenHashForUpdate(RefreshTokens.hash(old))).isPresent();
                locked.countDown();
                try {
                    release.await(60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            store.revokeAllOf(String.valueOf(customerId));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .as("창 밖 행까지 UPDATE 했다면 잠긴 행을 기다린다").isLessThan(java.time.Duration.ofSeconds(5));

            release.countDown();
            cleanup.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL AND expires_at > UTC_TIMESTAMP()",
                Integer.class, UuidBinary.toBytes(customerId))).as("창 안의 행은 폐기됐다").isZero();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM refresh_tokens WHERE token_hash = ?", Boolean.class, RefreshTokens.hash(old)))
                .as("창 밖 행은 그대로").isTrue();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM refresh_tokens WHERE token_hash = ?", Boolean.class, RefreshTokens.hash(justExpired)))
                .as("만료 10분 — 탐지 창 안이라 폐기된다(그 사이 나간 액세스 토큰이 아직 살아 있다)").isFalse();
    }

    @Test
    @DisplayName("revokeAllOf: 회원 id 가 아닌 subject(관리자)면 아무 행도 건드리지 않는다 — 관리자 세션은 이 표에 없다")
    void revokeAllOfIgnoresNonUuidSubject() {
        UUID customerId = newCustomer();
        login(customerId, UUID.randomUUID());

        store.revokeAllOf("admin");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL",
                Integer.class, UuidBinary.toBytes(customerId))).isEqualTo(1);
    }
}
