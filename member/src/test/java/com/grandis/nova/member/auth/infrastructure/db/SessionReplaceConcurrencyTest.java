package com.grandis.nova.member.auth.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.member.auth.application.RefreshTokenStore;
import com.grandis.nova.member.auth.application.RefreshTokenStore.Rotation;
import com.grandis.nova.member.auth.application.RefreshTokens;
import com.grandis.nova.member.support.MemberIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인(세션 교체)이 같은 회원의 로그인 · 회전과 겹칠 때 — 실제 MySQL(READ COMMITTED). 한쪽을 잠금을 쥔 채 세워 두고 다른 쪽이 잠그는
 * 문장(회원 행 FOR UPDATE · 리프레시 행 UPDATE)을 실행 중인 것을 PROCESSLIST 로 본 뒤 풀어, 겹치는 순서를 매번 같게 만든다.
 */
@MemberIntegrationTest
@DisplayName("세션 교체 — 같은 회원의 로그인 · 회전과 겹칠 때 (실제 MySQL)")
class SessionReplaceConcurrencyTest {

    private static final Duration FOURTEEN_DAYS = Duration.ofDays(14);

    @Autowired RefreshTokenStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean RefreshTokenRepository rows;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("동시에 두 로그인 — 늦은 쪽이 회원 행에서 기다렸다가 앞 쪽 세션을 끊는다. 살아 남는 세션은 늦은 쪽 하나")
    void concurrentLoginsLeaveOneSession() throws Exception {
        UUID customer = newCustomer();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);

        Future<List<UUID>> a = pool.submit(() -> transactions.execute(status -> {
            List<UUID> replaced = store.replace(first, String.valueOf(customer), RefreshTokens.newToken(), expiry());
            firstInserted.countDown();
            await(commitFirst);   // 회원 행 잠금 · 새 행을 쥔 채 커밋하지 않는다
            return replaced;
        }));
        await(firstInserted);
        Future<List<UUID>> b = pool.submit(() -> store.replace(second, String.valueOf(customer), RefreshTokens.newToken(), expiry()));
        waitUntilBlockedOrDone(b);
        commitFirst.countDown();

        assertThat(a.get(30, TimeUnit.SECONDS)).isEmpty();
        assertThat(b.get(30, TimeUnit.SECONDS)).as("늦은 로그인이 끊은 세션").containsExactly(first);
        assertThat(liveSessions(customer)).containsExactly(second.toString());
    }

    @Test
    @DisplayName("회전이 리프레시 행을 쥔 사이 로그인이 오면 교착 없이 둘 다 끝난다 — 회전이 회원 행을 먼저 공유 잠금으로 잡아 로그인이 기다린다")
    void rotationAndLoginDoNotDeadlock() throws Exception {
        UUID customer = newCustomer();
        UUID phone = UUID.randomUUID();
        String phoneRaw = RefreshTokens.newToken();
        store.replace(phone, String.valueOf(customer), phoneRaw, expiry());
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch resumeRotation = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        // JPA 저장소의 스파이는 인터페이스라 callRealMethod 가 안 된다(abstract) — 스파이의 기본 응답이 실제 저장소로 위임한다
        Answer<?> real = Mockito.mockingDetails(rows).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object locked = real.answer(invocation);
            if (pauseOnce.getAndSet(false)) {
                rowLocked.countDown();
                await(resumeRotation);   // 리프레시 행을 잠근 채, 새 행을 넣기 전에 선다
            }
            return locked;
        }).when(rows).findByTokenHashForUpdate(any());

        Future<Rotation> rotation = pool.submit(() -> store.rotate(phoneRaw, RefreshTokens.newToken()));
        await(rowLocked);
        UUID laptop = UUID.randomUUID();
        Future<List<UUID>> login = pool.submit(() -> store.replace(laptop, String.valueOf(customer), RefreshTokens.newToken(), expiry()));
        waitUntilBlockedOrDone(login);
        resumeRotation.countDown();

        assertThat(rotation.get(30, TimeUnit.SECONDS).status()).isEqualTo(Rotation.Status.ROTATED);
        assertThat(login.get(30, TimeUnit.SECONDS)).as("회전된 체인도 끊는다").containsExactly(phone);
        assertThat(liveSessions(customer)).containsExactly(laptop.toString());
    }

    @Test
    @DisplayName("로그인이 회원 행을 쥔 사이 앞 세션의 재발급이 오면 회원 행에서 기다렸다가 끊긴 것을 보고 REVOKED — 두 번째 살아 있는 세션이 생기지 않는다")
    void rotationAfterLoginCommitIsRevoked() throws Exception {
        UUID customer = newCustomer();
        UUID phone = UUID.randomUUID();
        String phoneRaw = RefreshTokens.newToken();
        store.replace(phone, String.valueOf(customer), phoneRaw, expiry());
        UUID laptop = UUID.randomUUID();
        CountDownLatch loggedIn = new CountDownLatch(1);
        CountDownLatch commitLogin = new CountDownLatch(1);

        Future<List<UUID>> login = pool.submit(() -> transactions.execute(status -> {
            List<UUID> replaced = store.replace(laptop, String.valueOf(customer), RefreshTokens.newToken(), expiry());
            loggedIn.countDown();
            await(commitLogin);   // 회원 행 잠금을 쥔 채 커밋하지 않는다
            return replaced;
        }));
        await(loggedIn);
        Future<Rotation> rotation = pool.submit(() -> store.rotate(phoneRaw, RefreshTokens.newToken()));
        waitUntilBlockedOrDone(rotation);
        commitLogin.countDown();

        assertThat(login.get(30, TimeUnit.SECONDS)).containsExactly(phone);
        assertThat(rotation.get(30, TimeUnit.SECONDS).status()).isEqualTo(Rotation.Status.REVOKED);
        assertThat(liveSessions(customer)).containsExactly(laptop.toString());
    }

    @Test
    @DisplayName("로그인이 회원 행을 잠근 사이 그 회원의 다른 세션 로그아웃이 오면 교착 없이 둘 다 끝난다 — 로그아웃도 회원 행을 먼저 공유 잠금으로 잡는다")
    void logoutDuringLoginDoesNotDeadlock() throws Exception {
        UUID customer = newCustomer();
        UUID phone = UUID.randomUUID();
        store.replace(phone, String.valueOf(customer), RefreshTokens.newToken(), expiry());
        CountDownLatch customerLocked = new CountDownLatch(1);
        CountDownLatch resumeLogin = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        Answer<?> real = Mockito.mockingDetails(rows).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object locked = real.answer(invocation);
            if (pauseOnce.getAndSet(false)) {
                customerLocked.countDown();
                await(resumeLogin);   // 회원 행을 잠근 채, 앞 세션을 폐기하기 전에 선다
            }
            return locked;
        }).when(rows).lockCustomer(any());

        UUID laptop = UUID.randomUUID();
        Future<List<UUID>> login = pool.submit(() -> store.replace(laptop, String.valueOf(customer), RefreshTokens.newToken(), expiry()));
        await(customerLocked);
        Future<?> logout = pool.submit(() -> store.revokeSession(phone));
        waitUntilBlockedOrDone(logout);
        resumeLogin.countDown();

        assertThat(login.get(30, TimeUnit.SECONDS)).containsExactly(phone);
        logout.get(30, TimeUnit.SECONDS);
        assertThat(liveSessions(customer)).containsExactly(laptop.toString());
    }

    private UUID newCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id, kakao_id, display_name, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                UuidBinary.toBytes(id), "s-" + UUID.randomUUID(), "교체시험");
        return id;
    }

    private static Instant expiry() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(FOURTEEN_DAYS);
    }

    private List<String> liveSessions(UUID customer) {
        return jdbc.queryForList("SELECT DISTINCT family_id FROM refresh_tokens WHERE customer_id = ? AND revoked_at IS NULL",
                String.class, (Object) UuidBinary.toBytes(customer));
    }

    /**
     * 다른 연결이 잠그는 문장(회원 행 FOR UPDATE · 리프레시 행 UPDATE)을 실행 중이거나 그 작업이 이미 끝났을 때까지. 세워 둔 쪽이 잠금을 쥐고 있어
     * 실행 중이면 곧 기다리는 중이다. 끝났다면 기다리지 않은 것이고 단언이 그걸 잡는다.
     */
    private void waitUntilBlockedOrDone(Future<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (task.isDone()) {
                return;
            }
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.PROCESSLIST
                     WHERE ID <> CONNECTION_ID()
                       AND (LOWER(INFO) LIKE '%from customers where id = %for %' OR LOWER(INFO) LIKE '%update refresh_tokens %')""",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("10초 안에 잠금 대기도 끝남도 없었다");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
