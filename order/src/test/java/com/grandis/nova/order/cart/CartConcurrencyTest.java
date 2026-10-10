package com.grandis.nova.order.cart;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.cart.domain.exception.CartSlotTakenException;
import com.grandis.nova.order.cart.domain.model.CartLine;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 같은 회원의 담기가 겹칠 때. 다른 트랜잭션을 잡아 두고 담기가 그 뒤에 줄을 서는지를 결정적으로 본다. */
@OrderIntegrationTest
@AutoConfigureMockMvc   // 쓰지 않지만 CartApiTest 와 같은 구성으로 두어 Spring 컨텍스트(와 MySQL 컨테이너)를 하나로 함께 쓴다
class CartConcurrencyTest {

    @Autowired CartService cart;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean CatalogClient catalogClient;
    @MockitoSpyBean CartStore store;
    @Autowired EntityManagerFactory entityManagerFactory;

    OrderFixtures fixtures;
    UUID customerId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        given(catalogClient.getOptions(any(), any())).willAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ApiResponse.ok(new CatalogOptions(ids.stream().map(CartConcurrencyTest::sellable).toList()));
        });
    }

    /**
     * 49줄에서 다른 트랜잭션이 줄을 잠근 채 50번째를 넣는 동안, 새 줄 담기는 그 잠금을 기다린다(1초 안에 끝나지 않는다).
     * 잠금이 풀리면 50줄을 보고 400 — 둘 다 49줄을 읽어 51줄이 되지 않는다.
     *
     * 앞선 트랜잭션이 넣는 줄은 옵션 id 가 가장 작아 유일 키 (customer_id, option_id, warranty_selected) 에서 기존 줄보다 앞에 놓인다.
     * 잠금 읽기는 기다리던 자리부터 이어 읽으므로 한 번의 잠금 읽기로는 이 줄을 놓친다 — 이 순서가 깨지는 쪽이다.
     * 다른 회원 줄을 깔아 운영처럼 그 유일 키로 훑는 계획(ref)인지 먼저 확인한다 — 49줄뿐이면 전체 훑기(ALL)라 놓침이 재현되지 않는다.
     */
    @Test
    void addWaitsForCustomerLinesLockAndSeesTheFiftiethLine() throws Exception {
        List<UUID> options = fixtures.inStockProduct(51).optionIds();
        options.forEach(option -> fixtures.stock(option, 10, 0, 0));
        UUID lowest = options.getFirst();
        seedOtherCustomersLines();
        transactionTemplate.executeWithoutResult(status -> options.subList(1, 50)
                .forEach(option -> store.insert(customerId, option, false, 1)));
        assertThat(jdbcTemplate.queryForMap("EXPLAIN SELECT id FROM cart_items WHERE customer_id = ? ORDER BY created_at, id FOR UPDATE",
                (Object) bytes(customerId)))
                .as("운영과 같은 계획").containsEntry("type", "ref").containsEntry("key", "uq_cart_customer_option_warranty");

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(store.lockByCustomer(customerId)).hasSize(49);
            locked.countDown();
            awaitQuietly(release);
            store.insert(customerId, lowest, false, 1);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<CartLine> adding = CompletableFuture.supplyAsync(() -> cart.add(customerId, "token", options.getLast(), 1, false));
        try {
            assertThat(adding).as("잠긴 동안 담기는 끝나지 않는다").failsWithin(Duration.ofSeconds(1))
                    .withThrowableOfType(TimeoutException.class);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(adding).failsWithin(Duration.ofSeconds(10)).withThrowableThat().havingRootCause()
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
                    assertThat(e.details().toString()).contains("variantId");
                });
        assertThat(lineCount()).isEqualTo(50);
    }

    /**
     * 빈 장바구니에 같은 (옵션, 보증)이 겹친다: 담기가 "줄 없음" 을 읽은 뒤 넣기 직전에 다른 요청이 같은 줄을 넣고 커밋한다.
     * 넣기는 유일 키 충돌이고, 한 번 다시 해 그 줄에 합산한다.
     */
    @Test
    void addRetriesOnceWhenSameSlotIsInsertedConcurrently() {
        UUID option = fixtures.inStockProduct(1).optionIds().getFirst();
        fixtures.stock(option, 10, 0, 0);
        AtomicBoolean raced = new AtomicBoolean();
        willAnswer(invocation -> {
            if (raced.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> jdbcTemplate.update("""
                        INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                        VALUES (?, ?, ?, 0, 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """, bytes(UUID.randomUUID()), bytes(customerId), bytes(option))).get(10, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).given(store).insert(any(), any(), anyBoolean(), anyInt());

        CartLine line = cart.add(customerId, "token", option, 3, false);

        assertThat(raced).isTrue();
        assertThat(line.quantity()).isEqualTo(5);
        assertThat(lineCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM cart_items WHERE customer_id = ?", Integer.class,
                (Object) bytes(customerId))).isEqualTo(5);
    }

    /** 다시 해도 같은 줄 넣기가 또 겹치면 500 이 아니라 409 STATE_CONFLICT — 두 번까지만 한다. */
    @Test
    void addGivesUpWithStateConflictAfterSecondCollision() {
        UUID option = fixtures.inStockProduct(1).optionIds().getFirst();
        fixtures.stock(option, 10, 0, 0);
        willThrow(new CartSlotTakenException(null)).given(store).insert(any(), any(), anyBoolean(), anyInt());

        assertThatThrownBy(() -> cart.add(customerId, "token", option, 1, false))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.STATE_CONFLICT));
        verify(store, times(CartService.WRITE_ATTEMPTS)).insert(any(), any(), anyBoolean(), anyInt());
        assertThat(lineCount()).isZero();
    }

    /**
     * 교착은 상대가 이미 끝나 있으므로 새 트랜잭션에서 한 번 더 한다 — 담기 · 수량 변경 · 삭제 모두.
     * 진짜 교착이면 InnoDB 가 그 트랜잭션을 되돌리므로 같은 트랜잭션에서 다시 하면 안 된다 — 시도마다 묶인 트랜잭션 자원이 다른지 본다.
     */
    @Test
    void deadlockIsRetriedInNewTransaction() {
        UUID option = fixtures.inStockProduct(1).optionIds().getFirst();
        fixtures.stock(option, 10, 0, 0);
        List<Object> transactions = new ArrayList<>();
        AtomicBoolean first = new AtomicBoolean(true);
        willAnswer(invocation -> {
            transactions.add(TransactionSynchronizationManager.getResource(entityManagerFactory));
            if (first.getAndSet(false)) {
                throw deadlock();
            }
            return invocation.callRealMethod();
        }).given(store).lockByCustomer(customerId);
        CartLine line = cart.add(customerId, "token", option, 1, false);
        assertThat(lineCount()).isEqualTo(1);
        assertThat(transactions).hasSize(2).doesNotContainNull();
        assertThat(transactions.get(0)).as("다시 하기는 새 트랜잭션").isNotSameAs(transactions.get(1));

        willThrow(deadlock()).willCallRealMethod().given(store).lockLine(customerId, line.id());
        assertThat(cart.changeQuantity(customerId, line.id(), 3).quantity()).isEqualTo(3);

        willThrow(deadlock()).willCallRealMethod().given(store).delete(customerId, line.id());
        cart.remove(customerId, line.id());
        assertThat(lineCount()).isZero();
    }

    /**
     * 장바구니 경로의 진짜 잠금 대기 초과(1205)가 교착이 아닌 잠금 실패로 갈려 다시 하지 않고 503 이다 — 합성 예외가 아니라 MySQL 의 오류로 본다.
     * 다른 트랜잭션이 그 회원의 줄을 잠근 채 두고, 담기의 연결만 잠금 대기를 1초로 줄인다(끝나면 되돌린다 — 풀의 연결이다).
     */
    @Test
    void realLockWaitTimeoutOnCartLinesIsUnavailableWithoutRetry() throws Exception {
        UUID option = fixtures.inStockProduct(1).optionIds().getFirst();
        fixtures.stock(option, 10, 0, 0);
        transactionTemplate.executeWithoutResult(status -> store.insert(customerId, option, false, 1));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForList("SELECT id FROM cart_items WHERE customer_id = ? FOR UPDATE", (Object) bytes(customerId));
            locked.countDown();
            awaitQuietly(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        willAnswer(invocation -> {
            Integer before = jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
            jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
            try {
                return invocation.callRealMethod();
            } finally {
                jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = " + before);
            }
        }).given(store).lockByCustomer(customerId);
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> cart.add(customerId, "token", option, 1, false))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
            // 1초 설정이 담기의 연결에 걸렸다는 대조 — 안 걸렸으면 기본 50초를 기다린 뒤 똑같이 503 이다
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            verify(store, times(1)).lockByCustomer(customerId);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    /** 다시 해도 교착이면 503. 잠금 대기 초과(1205)는 이미 오래 기다렸으므로 다시 하지 않고 503. */
    @Test
    void repeatedDeadlockAndLockWaitTimeoutAreUnavailable() {
        UUID option = fixtures.inStockProduct(1).optionIds().getFirst();
        fixtures.stock(option, 10, 0, 0);

        willThrow(deadlock()).given(store).lockByCustomer(customerId);
        assertThatThrownBy(() -> cart.add(customerId, "token", option, 1, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(store, times(CartService.WRITE_ATTEMPTS)).lockByCustomer(customerId);

        clearInvocations(store);
        willThrow(new CannotAcquireLockException("timeout", new SQLException("Lock wait timeout exceeded", "40001", 1205)))
                .given(store).lockByCustomer(customerId);
        assertThatThrownBy(() -> cart.add(customerId, "token", option, 1, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(store, times(1)).lockByCustomer(customerId);
        assertThat(lineCount()).isZero();
    }

    private static CannotAcquireLockException deadlock() {
        return new CannotAcquireLockException("deadlock",
                new SQLException("Deadlock found when trying to get lock", "40001", MySqlLockFailures.MYSQL_DEADLOCK));
    }

    /** 다른 회원 40명 × 50줄. 표가 49줄뿐이면 옵티마이저가 전체 훑기를 고른다. */
    private void seedOtherCustomersLines() {
        List<UUID> options = fixtures.inStockProduct(50).optionIds();
        List<Object[]> rows = new ArrayList<>();
        for (int c = 0; c < 40; c++) {
            byte[] other = bytes(fixtures.customer());
            options.forEach(option -> rows.add(new Object[]{bytes(UUID.randomUUID()), other, bytes(option)}));
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, 0, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, rows);
        jdbcTemplate.execute("ANALYZE TABLE cart_items");
    }

    private long lineCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_items WHERE customer_id = ?", Long.class, (Object) bytes(customerId));
    }

    private static CatalogOption sellable(UUID optionId) {
        return new CatalogOption(optionId, UUID.randomUUID(), "아이폰 17", "블랙 / 256GB", "SKU-" + optionId, new BigDecimal("1250000"),
                "ACTIVE", "IN_STOCK", "ACTIVE", true, true, new CatalogOption.Warranty(false, BigDecimal.ZERO), null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
