package com.grandis.nova.order.cart;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.model.CartLine;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/** 같은 회원의 담기가 겹칠 때. 다른 트랜잭션을 잡아 두고 담기가 그 뒤에 줄을 서는지를 결정적으로 본다. */
@OrderIntegrationTest
class CartConcurrencyTest {

    @Autowired CartService cart;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean CatalogClient catalogClient;
    @MockitoSpyBean CartStore store;

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
     */
    @Test
    void addWaitsForCustomerLinesLockAndSeesTheFiftiethLine() throws Exception {
        List<UUID> options = fixtures.inStockProduct(51).optionIds();
        options.forEach(option -> fixtures.stock(option, 10, 0, 0));
        transactionTemplate.executeWithoutResult(status -> options.subList(0, 49)
                .forEach(option -> store.insert(customerId, option, false, 1)));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(store.lockByCustomer(customerId)).hasSize(49);
            locked.countDown();
            awaitQuietly(release);
            store.insert(customerId, options.get(49), false, 1);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<CartLine> adding = CompletableFuture.supplyAsync(() -> cart.add(customerId, "token", options.get(50), 1, false));
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
