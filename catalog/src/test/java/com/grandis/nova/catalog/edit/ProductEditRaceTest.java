package com.grandis.nova.catalog.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.config.JpaAuditingConfig;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 수정이 서로 · 오픈 시각과 겹칠 때. 앱의 시계(storageClock)를 시험이 조종하고, 두 트랜잭션을 실제 커넥션 둘로 엇갈려 태운다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@DisplayName("관리자 수정의 경쟁 — 한 상품의 수정은 줄 서고, 오픈 판정은 커밋 직전 값으로 한다")
class ProductEditRaceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ControllableClock CLOCK = new ControllableClock();

    @TestBean(name = "storageClock", methodName = "controllableClock")
    Clock clock;

    @Autowired ProductEditService editService;
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    ShopFixtures fixtures;
    Long categoryId;

    static Clock controllableClock() {
        return CLOCK;
    }

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
    }

    @AfterEach
    void releaseClock() {
        CLOCK.next = null;
    }

    @Test
    @DisplayName("기본가 수정이 커밋 전에 추가금 수정이 들어오면 뒤의 것은 기다렸다가 새 기본가로 계산한다 — 계산 가격 = 기본가 + Σ추가금")
    void concurrentEditsSerializeOnTheProductRow() throws Exception {
        long productId = registerInStock();
        long storage512 = jdbcTemplate.queryForObject("""
                SELECT v.id FROM product_option_values v JOIN product_option_axes a ON a.id = v.axis_id
                 WHERE a.product_id = ? AND v.normalized_value = '512GB'
                """, Long.class, productId);
        // 첫 수정: 기본가 1,000,000 → 1,100,000. 둘째 수정: 512GB 추가금 200,000 → 300,000 — 첫 수정이 커밋되기 전에 시작한다
        interleave(() -> editService.editProduct(productId, new ProductEditRequest(null, null, null, new BigDecimal("1100000"), null)),
                () -> editService.editOptionValue(productId, storage512, new OptionValueEditRequest(null, new BigDecimal("300000"))));

        assertThat(price(productId, "256GB")).isEqualByComparingTo("1100000");
        assertThat(price(productId, "512GB")).as("새 기본가 1,100,000 + 새 추가금 300,000 — 옛 기본가로 계산하면 1,300,000")
                .isEqualByComparingTo("1400000");
    }

    @Test
    @DisplayName("옵션 수동 가격이 커밋되기 전에 기본가 수정이 들어와도 수동 가격은 남는다 — 재계산은 수동 표시가 커밋된 뒤의 옵션을 읽는다")
    void manualPriceSurvivesAConcurrentRecompute() throws Exception {
        long productId = registerInStock();
        long option512 = optionId(productId, "512GB");
        interleave(() -> editService.editVariant(productId, option512, new VariantEditRequest(new BigDecimal("1270000"), null)),
                () -> editService.editProduct(productId, new ProductEditRequest(null, null, null, new BigDecimal("1100000"), null)));

        assertThat(price(productId, "512GB")).as("수동 가격 — 옛 상태(수동 아님)로 읽은 재계산이 덮으면 1,300,000").isEqualByComparingTo("1270000");
        assertThat(jdbcTemplate.queryForObject("SELECT price_overridden FROM product_options WHERE id = ?", Boolean.class, option512)).isTrue();
        assertThat(price(productId, "256GB")).isEqualByComparingTo("1100000");
    }

    @Test
    @DisplayName("같은 축에 값 둘이 동시에 더해져도 순서(position)가 겹치지 않는다 — 뒤의 것은 앞의 값이 커밋된 뒤 최댓값을 읽는다")
    void concurrentValueAddsGetDistinctPositions() throws Exception {
        long productId = registerInStock();
        interleave(() -> editService.addOptionValue(productId, new OptionValueAddRequest("storage", "1TB", null)),
                () -> editService.addOptionValue(productId, new OptionValueAddRequest("storage", "2TB", null)));

        assertThat(jdbcTemplate.queryForList("""
                SELECT v.position FROM product_option_values v JOIN product_option_axes a ON a.id = v.axis_id
                 WHERE a.product_id = ? ORDER BY v.position
                """, Integer.class, productId)).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("다른 모듈의 외래키 확인과 교착해 수정이 희생되면 409 STATE_CONFLICT · retryable — 500 이 아니고 수정은 통째로 되돌려진다")
    void deadlockVictimIsRetryableConflict() throws Exception {
        long productId = registerInStock();
        long optionA = optionId(productId, "256GB");
        long optionB = optionId(productId, "512GB");
        long customerId = customer();
        CountDownLatch cartHoldsB = new CountDownLatch(1);
        CountDownLatch insertA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 장바구니(다른 모듈): B 를 먼저, A 를 나중에 담는다 — 외래키 확인이 옵션 행에 공유 잠금을 id 역순으로 건다
            Future<?> cart = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                cartItem(customerId, optionB);
                cartHoldsB.countDown();
                await(insertA);
                cartItem(customerId, optionA);
            }));
            assertThat(cartHoldsB.await(30, TimeUnit.SECONDS)).isTrue();
            // 기본가 수정: 옵션을 id 순(A → B)으로 고친다. A 를 잡고 B 에서 기다린다
            Future<org.springframework.test.web.servlet.MvcResult> edit = pool.submit(() -> mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/products/{id}", productId)
                            .contentType(MediaType.APPLICATION_JSON).with(user("admin").roles("ADMIN"))
                            .content("{ \"basePrice\": 1100000 }")).andReturn());
            awaitBlockedOrDone(edit);
            insertA.countDown();   // 장바구니가 A 를 기다린다 → 교착
            String body = edit.get(60, TimeUnit.SECONDS).getResponse().getContentAsString();
            int statusCode = edit.get().getResponse().getStatus();
            try {
                cart.get(60, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ignored) {
                // 장바구니 쪽이 희생자로 골렸으면 여기로 온다 — 그때는 수정이 성공한다
            }
            assertThat(statusCode).as(body).isNotEqualTo(500);
            if (statusCode == 409) {
                var error = JSON.readTree(body).get("error");
                assertThat(error.get("code").asString()).isEqualTo("STATE_CONFLICT");
                assertThat(error.get("details").get("retryable").asBoolean()).isTrue();
                assertThat(price(productId, "256GB")).as("되돌려졌다").isEqualByComparingTo("1000000");
            } else {
                assertThat(statusCode).isEqualTo(200);
                assertThat(price(productId, "256GB")).isEqualByComparingTo("1100000");
            }
            victims.add(statusCode);
        } finally {
            insertA.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("오픈 시각 그 순간(opens_at == 지금)은 오픈 뒤다 — 409. 1마이크로초 전은 고칠 수 있다")
    void openBoundaryIsInclusive() throws Exception {
        long productId = registerPreorder();
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(1)));

        CLOCK.next = () -> opensAt;
        assertStateConflict(() -> editService.editProduct(productId, titleOnly("그 순간")));
        CLOCK.next = () -> opensAt.minus(1, ChronoUnit.MICROS);
        editService.editProduct(productId, titleOnly("직전"));
        assertThat(title(productId)).isEqualTo("직전");
    }

    @Test
    @DisplayName("시작 때는 오픈 전이었는데 수정 중에 오픈 시각이 지나면 커밋하지 않는다 — 409, 아무것도 안 남는다")
    void openingDuringTheEditRejectsTheCommit() throws Exception {
        long productId = registerPreorder();
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(1)));

        AtomicInteger reads = new AtomicInteger();
        CLOCK.next = () -> reads.getAndIncrement() == 0 ? opensAt.minusMillis(1) : opensAt;   // 첫 판정 뒤 시간이 오픈을 넘는다
        assertStateConflict(() -> editService.editProduct(productId, titleOnly("오픈을 넘긴 수정")));
        assertThat(reads.get()).as("시계를 두 번 이상 읽었다 — 첫 판정은 통과했다").isGreaterThanOrEqualTo(2);
        assertThat(title(productId)).isEqualTo("Race");
    }

    @Test
    @DisplayName("수정 중에 preorder 가 회차 오픈을 앞당겨 이미 열렸으면 커밋하지 않는다 — 커밋 직전 판정은 그때까지 커밋된 회차를 본다")
    void campaignMovedEarlierDuringTheEditRejectsTheCommit() throws Exception {
        long productId = registerPreorder();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, now.plus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)));

        AtomicInteger reads = new AtomicInteger();
        CLOCK.next = () -> {
            if (reads.getAndIncrement() == 0) {
                // 첫 판정 순간, 다른 커넥션(preorder)이 오픈을 1분 전으로 옮겨 커밋한다
                moveOpensAtFromAnotherConnection(productId, now.minus(Duration.ofMinutes(1)));
            }
            return now;
        };
        assertStateConflict(() -> editService.editProduct(productId, titleOnly("회차가 옮겨진 수정")));
        assertThat(reads.get()).as("첫 판정은 옮기기 전 회차로 통과했다").isGreaterThanOrEqualTo(2);
        assertThat(title(productId)).isEqualTo("Race");
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

    /** 교착 시험에서 어느 쪽이 희생됐는지(409 = 수정, 200 = 장바구니). 로그로 남겨 매핑 갈래를 실제로 탔는지 본다. */
    private static final java.util.List<Integer> victims = new java.util.concurrent.CopyOnWriteArrayList<>();

    @org.junit.jupiter.api.AfterAll
    static void reportVictims() {
        System.out.println("DEADLOCK-EDIT-STATUS " + victims);
    }

    /**
     * 첫 수정을 바깥 트랜잭션에 합류시켜 커밋 직전에 붙잡고, 그동안 둘째 수정을 시작한다. 둘째가 DB 에서 기다리거나(잠금) 끝나면(잠금이 없는
     * 구현) 첫째를 놓아 준다 — 잠금이 없으면 둘째가 먼저 끝나 결과가 틀어지는 것을 시험이 본다.
     */
    private void interleave(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRun = pool.submit(() -> transaction.executeWithoutResult(status -> {
                first.run();
                firstDone.countDown();
                await(releaseFirst);
            }));
            assertThat(firstDone.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> secondRun = pool.submit(second);
            awaitBlockedOrDone(secondRun);
            releaseFirst.countDown();
            firstRun.get(30, TimeUnit.SECONDS);
            secondRun.get(30, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private long optionId(long productId, String title) {
        return jdbcTemplate.queryForObject("SELECT id FROM product_options WHERE product_id = ? AND title = ?", Long.class, productId, title);
    }

    /** member 소유 표 — 장바구니 외래키를 채우려고 시험 데이터로만 넣는다. */
    private long customer() {
        String kakaoId = "k-" + ShopFixtures.unique();
        jdbcTemplate.update("INSERT INTO customers (kakao_id, display_name, created_at, updated_at) VALUES (?, 'race', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", kakaoId);
        return jdbcTemplate.queryForObject("SELECT id FROM customers WHERE kakao_id = ?", Long.class, kakaoId);
    }

    /** order 소유 표 — 다른 모듈의 쓰기를 흉내 낸다(외래키 확인이 옵션 행에 공유 잠금). */
    private void cartItem(long customerId, long optionId) {
        jdbcTemplate.update("INSERT INTO cart_items (customer_id, option_id, quantity, created_at, updated_at) VALUES (?, ?, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                customerId, optionId);
    }

    private static ProductEditRequest titleOnly(String title) {
        return new ProductEditRequest(title, null, null, null, null);
    }

    private static void assertStateConflict(Runnable edit) {
        assertThatThrownBy(edit::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CatalogErrorCode.STATE_CONFLICT));
    }

    /**
     * 둘째 작업이 DB 에서 잠금을 기다리는 중이거나 이미 끝났을 때까지 기다린다. 같은 사용자의 스레드는 PROCESS 권한 없이 PROCESSLIST 에 보이고,
     * 쉬는 커넥션은 COMMAND=Sleep 이다. 다른 커넥션의 Query 가 하나라도 보이면 기다리는 것으로 본다(다른 이유로 걸려도 거짓 통과는 아니다 —
     * 결과 단언이 따로 가른다).
     */
    private void awaitBlockedOrDone(Future<?> second) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (second.isDone()) {
                return;
            }
            Long running = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.PROCESSLIST
                     WHERE COMMAND = 'Query' AND ID <> CONNECTION_ID() AND INFO IS NOT NULL
                    """, Long.class);
            if (running != null && running > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("둘째 작업이 30초 안에 기다리지도 끝나지도 않았다");
    }

    private void moveOpensAtFromAnotherConnection(long productId, Instant opensAt) {
        Thread mover = new Thread(() -> jdbcTemplate.update("UPDATE preorder_campaigns SET opens_at = ? WHERE product_id = ?",
                LocalDateTime.ofInstant(opensAt, ZoneOffset.UTC), productId));
        mover.start();
        try {
            mover.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("released too late");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private BigDecimal price(long productId, String storage) {
        return jdbcTemplate.queryForObject("SELECT price FROM product_options WHERE product_id = ? AND title = ?",
                BigDecimal.class, productId, storage);
    }

    private String title(long productId) {
        return jdbcTemplate.queryForObject("SELECT title FROM products WHERE id = ?", String.class, productId);
    }

    /** 축 storage 하나(256GB +0 · 512GB +200,000), 기본가 1,000,000, 수동 가격 없음. */
    private long registerInStock() throws Exception {
        return register("""
                { "categoryId": %d, "saleMode": "IN_STOCK", "title": "Race", "visible": false, "basePrice": 1000000,
                  "optionAxes": [ { "key": "storage", "label": "용량", "values": [ { "value": "256GB" }, { "value": "512GB", "surcharge": 200000 } ] } ],
                  "combinations": [ { "selections": { "storage": "256GB" }, "stock": 1 }, { "selections": { "storage": "512GB" }, "stock": 1 } ] }
                """.formatted(categoryId));
    }

    private long registerPreorder() throws Exception {
        Instant opensAt = Instant.now().plus(Duration.ofHours(2));
        return register("""
                { "categoryId": %d, "saleMode": "PREORDER", "title": "Race", "visible": false, "basePrice": 1000000,
                  "optionAxes": [ { "key": "storage", "label": "용량", "values": [ { "value": "256GB" } ] } ],
                  "combinations": [ { "selections": { "storage": "256GB" } } ],
                  "campaign": { "opensAt": "%s", "closesAt": "%s" },
                  "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ] }
                """.formatted(categoryId, opensAt, opensAt.plus(Duration.ofDays(3))));
    }

    private long register(String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON)
                        .with(user("admin").roles("ADMIN")).header("Idempotency-Key", "k-" + ShopFixtures.unique()).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("data").get("registration").get("productId").asLong();
    }

    /** 평소엔 앱과 같은 시계(마이크로초 해상도의 UTC). 시험이 next 를 채우면 그 값을 낸다. */
    static final class ControllableClock extends Clock {

        private final Clock system = JpaAuditingConfig.atStorageResolution(Clock.systemUTC());
        volatile Supplier<Instant> next;

        @Override
        public Instant instant() {
            Supplier<Instant> supplier = next;
            return supplier == null ? system.instant() : supplier.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
