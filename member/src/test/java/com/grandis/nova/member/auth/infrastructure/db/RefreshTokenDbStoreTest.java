package com.grandis.nova.member.auth.infrastructure.db;

import com.grandis.nova.member.support.Concurrently;
import com.grandis.nova.member.support.MemberIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.member.MemberApplication;
import com.grandis.nova.member.TestKeys;
import com.grandis.nova.member.auth.application.ClientInfo;
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



    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.9", "JUnit");
    private static final java.time.Duration FOURTEEN_DAYS = java.time.Duration.ofDays(14);

    @Autowired RefreshTokenStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired RefreshTokenRepository rows;
    @Autowired TransactionTemplate transactions;

    private long newCustomer() {
        String kakaoId = "k-" + UUID.randomUUID();
        jdbc.update("INSERT INTO customers(kakao_id, display_name, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                kakaoId, "회전시험");
        return jdbc.queryForObject("SELECT id FROM customers WHERE kakao_id = ?", Long.class, kakaoId);
    }

    private static Instant nowSeconds() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 세션 하나를 만들고 첫 원문을 돌려준다. */
    private String login(long customerId, UUID sessionId) {
        String raw = RefreshTokens.newToken();
        store.save(sessionId, String.valueOf(customerId), raw, nowSeconds().plus(FOURTEEN_DAYS), CLIENT);
        return raw;
    }

    private List<String> familyRows(UUID sessionId) {
        return jdbc.queryForList(
                "SELECT CONCAT(id, ':', IF(rotated_at IS NULL, '-', 'rotated'), ':', IF(revoked_at IS NULL, '-', 'revoked')) "
                        + "FROM refresh_tokens WHERE family_id = ? ORDER BY id", String.class, sessionId.toString());
    }

    // ────────────────────────────── 발급·식별

    @Test
    @DisplayName("save: 원문은 어디에도 없고 SHA-256 32바이트만 남는다. 체인 식별자는 세션(sid)이다")
    void saveStoresOnlyTheHash() {
        long customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = nowSeconds().plus(FOURTEEN_DAYS);

        String raw = RefreshTokens.newToken();
        store.save(sessionId, String.valueOf(customerId), raw, expiresAt, CLIENT);

        var row = jdbc.queryForMap("SELECT * FROM refresh_tokens WHERE family_id = ?", sessionId.toString());
        assertThat((byte[]) row.get("token_hash")).hasSize(32).isEqualTo(RefreshTokens.hash(raw));
        assertThat(row.get("customer_id")).isEqualTo(customerId);
        assertThat(row.get("client_ip")).isEqualTo("203.0.113.9");
        assertThat(row.get("user_agent")).isEqualTo("JUnit");
        assertThat(row.get("rotated_at")).isNull();
        assertThat(row.get("revoked_at")).isNull();
        assertThat((LocalDateTime) row.get("expires_at")).isEqualTo(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        // 원문이 어느 칸에도 그대로 들어가 있지 않다
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(v -> v.contains(raw));
    }

    @Test
    @DisplayName("find: 원문으로 주인과 세션을 찾는다. 모르는 원문은 없음")
    void findResolvesTheOwner() {
        long customerId = newCustomer();
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
        long customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String raw = login(customerId, sessionId);
        LocalDateTime firstExpiry = jdbc.queryForObject(
                "SELECT expires_at FROM refresh_tokens WHERE family_id = ?", LocalDateTime.class, sessionId.toString());

        for (int i = 0; i < 3; i++) {
            String next = RefreshTokens.newToken();
            Rotation rotation = store.rotate(raw, next, CLIENT);
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
    @DisplayName("rotate 재사용: 이미 교체된 원문이 다시 오면 그 체인의 살아 있는 행이 **전부** 폐기되고, 그 폐기는 커밋된다")
    void reuseRevokesTheWholeFamilyAndCommits() {
        long customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String first = login(customerId, sessionId);
        String second = RefreshTokens.newToken();
        assertThat(store.rotate(first, second, CLIENT).status()).isEqualTo(Rotation.Status.ROTATED);

        Rotation reuse = store.rotate(first, RefreshTokens.newToken(), CLIENT);

        assertThat(reuse.status()).isEqualTo(Rotation.Status.REUSED);
        assertThat(reuse.sessionId()).isEqualTo(sessionId);
        // 폐기가 커밋됐는지는 다른 트랜잭션에서 읽어 확인한다 — 예외로 빠져나갔으면 여기서 0 이 나온다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NULL",
                Integer.class, sessionId.toString())).isZero();
        // 살아남은 토큰이 없다 — 탈취자도 원 소유자도 더 못 쓴다
        assertThat(store.rotate(second, RefreshTokens.newToken(), CLIENT).status()).isEqualTo(Rotation.Status.REVOKED);
    }

    @Test
    @DisplayName("rotate: 모르는 원문 · 폐기된 토큰 · 만료된 토큰은 각각 다른 사유로 거절된다")
    void rejectionsAreDistinguished() {
        long customerId = newCustomer();
        assertThat(store.rotate(RefreshTokens.newToken(), RefreshTokens.newToken(), CLIENT).status())
                .isEqualTo(Rotation.Status.NOT_FOUND);

        UUID revokedSession = UUID.randomUUID();
        String revoked = login(customerId, revokedSession);
        store.revokeSession(revokedSession);
        assertThat(store.rotate(revoked, RefreshTokens.newToken(), CLIENT).status()).isEqualTo(Rotation.Status.REVOKED);

        // 만료된 행은 직접 넣는다. ck_refresh_expiry 가 생성 < 만료를 강제하므로 생성 시각도 과거로 둔다.
        UUID expiredSession = UUID.randomUUID();
        String expired = RefreshTokens.newToken();
        Instant now = nowSeconds();
        jdbc.update("INSERT INTO refresh_tokens(customer_id, family_id, token_hash, expires_at, created_at) VALUES (?,?,?,?,?)",
                customerId, expiredSession.toString(), RefreshTokens.hash(expired),
                LocalDateTime.ofInstant(now.minus(1, ChronoUnit.HOURS), ZoneOffset.UTC),
                LocalDateTime.ofInstant(now.minus(15, ChronoUnit.DAYS), ZoneOffset.UTC));
        assertThat(store.rotate(expired, RefreshTokens.newToken(), CLIENT).status()).isEqualTo(Rotation.Status.EXPIRED);
    }

    @Test
    @DisplayName("만료 경계: 만료 시각과 같은 초는 이미 만료다. 한 초 앞이면 회전한다")
    void expiryBoundaryIsInclusive() {
        // 만료 판정은 `!expiresAt.isAfter(now)` 다. 경계인 `expiresAt == now` 를 안 태우면
        // 부등호를 한 칸 옮긴 구현도 통과한다 — 액세스 토큰의 `nbf == iat` 를 못 박은 것과 같은 이유다.
        // 저장소의 시계를 고정해 그 순간을 정확히 만든다. 실제 시각으로는 초가 넘어가 경계를 못 맞춘다.
        long customerId = newCustomer();
        Instant expiry = nowSeconds().plus(1, ChronoUnit.HOURS);
        String oneSecondEarly = expiring(customerId, expiry);
        String atBoundary = expiring(customerId, expiry);

        assertThat(rotateAt(expiry.minusSeconds(1), oneSecondEarly).status()).isEqualTo(Rotation.Status.ROTATED);
        assertThat(rotateAt(expiry, atBoundary).status()).isEqualTo(Rotation.Status.EXPIRED);
    }

    /** 만료 시각을 지정한 행 하나. ck_refresh_expiry 가 생성 < 만료를 강제하므로 생성은 하루 전으로 둔다. */
    private String expiring(long customerId, Instant expiresAt) {
        String raw = RefreshTokens.newToken();
        jdbc.update("INSERT INTO refresh_tokens(customer_id, family_id, token_hash, expires_at, created_at) VALUES (?,?,?,?,?)",
                customerId, UUID.randomUUID().toString(), RefreshTokens.hash(raw),
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                LocalDateTime.ofInstant(expiresAt.minus(1, ChronoUnit.DAYS), ZoneOffset.UTC));
        return raw;
    }

    /** 시계를 고정한 저장소로 회전한다. 직접 만든 객체라 @Transactional 이 안 붙으므로 트랜잭션을 밖에서 연다. */
    private Rotation rotateAt(Instant at, String presented) {
        DbRefreshTokenStore fixed = new DbRefreshTokenStore(rows, Clock.fixed(at, ZoneOffset.UTC));
        return transactions.execute(status -> fixed.rotate(presented, RefreshTokens.newToken(), CLIENT));
    }

    @Test
    @DisplayName("같은 원문으로 8개가 동시에 회전하면 정확히 하나만 성공한다 — 행 잠금이 직렬화하고 나머지는 재사용으로 본다")
    void concurrentRotationLetsExactlyOneThrough() throws Exception {
        long customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String raw = login(customerId, sessionId);
        int n = 8;
        List<Concurrently.Outcome<Rotation>> results =
                Concurrently.run(n, i -> () -> store.rotate(raw, RefreshTokens.newToken(), CLIENT));

        assertThat(results).allSatisfy(r -> assertThat(r.error()).isNull());
        List<Rotation.Status> outcomes = results.stream().map(r -> r.value().status()).toList();
        // 하나만 통과한다. 진 쪽 중 첫 번째는 "이미 교체됨"(REUSED)을 보고 체인을 끊고, 그 뒤에 잠금을 얻은 쪽들은
        // 이미 끊긴 행을 보므로 REVOKED 다. 둘 다 401 로 끝나는 같은 사건의 앞뒤다.
        assertThat(outcomes).filteredOn(s -> s == Rotation.Status.ROTATED).hasSize(1);
        assertThat(outcomes).filteredOn(s -> s == Rotation.Status.REUSED).isNotEmpty();
        assertThat(outcomes).allMatch(s -> s == Rotation.Status.ROTATED || s == Rotation.Status.REUSED || s == Rotation.Status.REVOKED);
        // 진 쪽들이 재사용으로 판정해 체인을 끊었다 — 이긴 쪽의 새 토큰도 함께 죽는다(정상 동작, RFC 9700)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = ? AND revoked_at IS NULL",
                Integer.class, sessionId.toString())).isZero();
        assertThat(familyRows(sessionId)).hasSize(2);   // 첫 발급 + 이긴 회전 하나
    }

    // ────────────────────────────── 폐기

    @Test
    @DisplayName("revokeSession: 체인 전체를 끊는다. 이미 끊긴 행의 시각은 덮지 않는다")
    void revokeSessionClosesTheChain() {
        long customerId = newCustomer();
        UUID sessionId = UUID.randomUUID();
        String raw = login(customerId, sessionId);
        store.rotate(raw, RefreshTokens.newToken(), CLIENT);

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
        long target = newCustomer();
        long bystander = newCustomer();
        UUID phone = UUID.randomUUID();
        UUID laptop = UUID.randomUUID();
        login(target, phone);
        login(target, laptop);
        UUID other = UUID.randomUUID();
        login(bystander, other);

        store.revokeAllOf(String.valueOf(target));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL",
                Integer.class, target)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL",
                Integer.class, bystander)).isEqualTo(1);
    }

    @Test
    @DisplayName("revokeAllOf: 회원 번호가 아닌 subject(관리자)면 아무 행도 건드리지 않는다 — 관리자 세션은 이 표에 없다")
    void revokeAllOfIgnoresNonNumericSubject() {
        long customerId = newCustomer();
        login(customerId, UUID.randomUUID());

        store.revokeAllOf("admin");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL",
                Integer.class, customerId)).isEqualTo(1);
    }
}
