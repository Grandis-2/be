package com.grandis.nova.catalog.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.jpa.StorageClock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    @MockitoSpyBean ProductEditService editService;
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    ShopFixtures fixtures;
    UUID categoryId;

    static Clock controllableClock() {
        return CLOCK;
    }

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
        CLOCK.owner = Thread.currentThread();
    }

    @AfterEach
    void releaseClock() {
        CLOCK.next = null;
    }

    @Test
    @DisplayName("기본가 수정이 커밋 전에 추가금 수정이 들어오면 뒤의 것은 기다렸다가 새 기본가로 계산한다 — 계산 가격 = 기본가 + Σ추가금")
    void concurrentEditsSerializeOnTheProductRow() throws Exception {
        UUID productId = registerInStock();
        String storage512 = fixtures.valueId(productId, "storage", "512GB");
        // 첫 수정: 기본가 1,000,000 → 1,100,000. 둘째 수정: 512GB 추가금 200,000 → 300,000 — 첫 수정이 커밋되기 전에 시작한다
        interleave(() -> editService.editProduct(productId, new ProductEditRequest(null, null, null, new BigDecimal("1100000"), null)),
                () -> editService.editOptionValue(productId, storage512, new OptionValueEditRequest(null, new BigDecimal("300000"), null)));

        assertThat(price(productId, "256GB")).isEqualByComparingTo("1100000");
        assertThat(price(productId, "512GB")).as("새 기본가 1,100,000 + 새 추가금 300,000 — 옛 기본가로 계산하면 1,300,000")
                .isEqualByComparingTo("1400000");
    }

    @Test
    @DisplayName("기본가 수정이 커밋 전에 옵션 상태 수정이 들어오면 뒤의 것은 기다렸다가 새 가격 위에 상태만 바꾼다 — 재계산된 가격을 옛 값으로 덮지 않는다")
    void variantEditAfterRecomputeKeepsTheNewPrice() throws Exception {
        UUID productId = registerInStock();
        UUID option512 = optionId(productId, "512GB");
        interleave(() -> editService.editProduct(productId, new ProductEditRequest(null, null, null, new BigDecimal("1100000"), null)),
                () -> editService.editVariant(productId, option512, new VariantEditRequest(SaleStatus.PAUSED)));

        assertThat(optionStatus(option512)).isEqualTo("PAUSED");
        assertThat(price(productId, "512GB")).as("새 기본가 1,100,000 + 추가금 200,000 — 옛 가격을 읽어 두고 쓰면 1,200,000")
                .isEqualByComparingTo("1300000");
    }

    @Test
    @DisplayName("옵션 상태 수정이 커밋 전에 기본가 수정이 들어오면 뒤의 것은 기다렸다가 바뀐 상태를 지킨 채 가격만 다시 계산한다")
    void recomputeAfterVariantEditKeepsTheNewStatus() throws Exception {
        UUID productId = registerInStock();
        UUID option512 = optionId(productId, "512GB");
        interleave(() -> editService.editVariant(productId, option512, new VariantEditRequest(SaleStatus.PAUSED)),
                () -> editService.editProduct(productId, new ProductEditRequest(null, null, null, new BigDecimal("1100000"), null)));

        assertThat(optionStatus(option512)).as("옛 상태를 읽어 두고 쓰면 ACTIVE 로 되돌아간다").isEqualTo("PAUSED");
        assertThat(price(productId, "512GB")).isEqualByComparingTo("1300000");
    }

    @Test
    @DisplayName("같은 축에 값 둘이 동시에 더해져도 둘 다 남는다 — 문서를 통째로 다시 쓰지만 뒤의 것은 앞의 값이 커밋된 문서를 읽는다")
    void concurrentValueAddsBothSurvive() throws Exception {
        UUID productId = registerInStock();
        interleave(() -> editService.addOptionValue(productId, new OptionValueAddRequest("storage", "1TB", null, null)),
                () -> editService.addOptionValue(productId, new OptionValueAddRequest("storage", "2TB", null, null)));

        assertThat(jdbcTemplate.queryForList("""
                SELECT j.v FROM products p,
                       JSON_TABLE(p.options, '$.axes[*]' COLUMNS (k VARCHAR(40) PATH '$.key',
                           NESTED PATH '$.values[*]' COLUMNS (v VARCHAR(60) PATH '$.normalized'))) j
                 WHERE p.id = ? AND j.k = 'storage'
                """, String.class, UuidBinary.toBytes(productId))).containsExactlyInAnyOrder("256GB", "512GB", "1TB", "2TB");
    }

    @Test
    @DisplayName("다른 모듈의 외래키 확인과 교착해 수정이 희생되면 409 STATE_CONFLICT · retryable — 500 이 아니고 수정은 통째로 되돌려진다")
    void deadlockVictimIsRetryableConflict() throws Exception {
        UUID productId = registerInStock();
        UUID optionA = optionId(productId, "256GB");
        UUID optionB = optionId(productId, "512GB");
        UUID customerId = customer();
        CountDownLatch cartHoldsB = new CountDownLatch(1);
        CountDownLatch insertA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 장바구니(다른 모듈): B 를 먼저, A 를 나중에 담는다 — 외래키 확인이 옵션 행에 공유 잠금을 수정과 반대 순서로 건다
            Future<?> cart = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                cartItem(customerId, optionB);
                cartHoldsB.countDown();
                await(insertA);
                cartItem(customerId, optionA);
            }));
            assertThat(cartHoldsB.await(30, TimeUnit.SECONDS)).isTrue();
            // 기본가 수정: 옵션을 생성 순(A → B)으로 고친다. A 를 잡고 B 에서 기다린다
            Future<org.springframework.test.web.servlet.MvcResult> edit = pool.submit(() -> mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/products/{id}", productId)
                            .contentType(MediaType.APPLICATION_JSON).with(user("admin").roles("ADMIN"))
                            .content("{ \"basePrice\": 1100000 }")).andReturn());
            awaitOptionUpdateBlockedOrDone(edit);   // 수정이 A 를 잡고 B 에서 기다릴 때까지 — 그 전에 놓으면 장바구니가 A 를 먼저 넣고 교착 없이 지나갈 수 있다
            insertA.countDown();   // 장바구니가 A 를 기다린다 → 교착
            String body = edit.get(60, TimeUnit.SECONDS).getResponse().getContentAsString();
            int statusCode = edit.get().getResponse().getStatus();
            Throwable cartFailure = null;
            try {
                cart.get(60, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                cartFailure = e.getCause();
            }
            // 희생자는 InnoDB 가 고른다(MySQL 8.4 에서는 수정 쪽이 희생됐다 — 작성자 1/1 · 리뷰어 6/6). 어느 쪽이든 교착이 실제로 났고
            // 500 이 아니며 남은 상태가 일관되는지를 본다. 409 매핑 자체는 아래 mappingOfLockFailure 가 희생자와 무관하게 결정적으로 시험한다
            assertThat(statusCode).as(body).isIn(200, 409);
            if (statusCode == 409) {
                var error = JSON.readTree(body).get("error");
                assertThat(error.get("code").asString()).isEqualTo("STATE_CONFLICT");
                assertThat(error.get("details").get("retryable").asBoolean()).isTrue();
                assertThat(cartFailure).as("수정이 희생됐으면 장바구니는 살아남는다").isNull();
                assertThat(price(productId, "256GB")).as("수정은 통째로 되돌려졌다").isEqualByComparingTo("1000000");
            } else {
                assertThat(cartFailure).as("수정이 성공했으면 장바구니가 교착 희생자다 — 교착 없이 지나간 실행을 통과시키지 않는다")
                        .isNotNull();
                assertThat(rootMessage(cartFailure)).containsIgnoringCase("deadlock");
                assertThat(price(productId, "256GB")).isEqualByComparingTo("1100000");
            }
        } finally {
            insertA.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("수정이 잠금 실패(교착 희생 · 잠금 대기 초과)로 끝나면 409 STATE_CONFLICT · retryable — 희생자 선택과 무관하게 매핑만 본다")
    void mappingOfLockFailure() throws Exception {
        UUID productId = registerInStock();
        doThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"))
                .when(editService).editProduct(any(), any());

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/admin/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON).with(user("admin").roles("ADMIN")).content("{ \"basePrice\": 1100000 }"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        var error = JSON.readTree(body).get("error");
        assertThat(error.get("code").asString()).isEqualTo("STATE_CONFLICT");
        assertThat(error.get("details").get("retryable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("오픈 3분 전 그 순간(지금 == opens_at − 3분)부터 잠긴다 — 409. 1마이크로초 전은 고칠 수 있다")
    void freezeBoundaryIsInclusive() throws Exception {
        UUID productId = registerPreorder();
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(1)));
        Instant freezesAt = opensAt.minus(ProductEditService.FREEZE_BEFORE_OPEN);
        assertThat(freezesAt).isEqualTo(opensAt.minus(Duration.ofMinutes(3)));

        CLOCK.next = () -> freezesAt;
        assertStateConflict(() -> editService.editProduct(productId, titleOnly("그 순간")));
        CLOCK.next = () -> opensAt;
        assertStateConflict(() -> editService.editProduct(productId, titleOnly("오픈")));
        CLOCK.next = () -> freezesAt.minus(1, ChronoUnit.MICROS);
        editService.editProduct(productId, titleOnly("직전"));
        assertThat(title(productId)).isEqualTo("직전");
    }

    @Test
    @DisplayName("시작 때는 잠금 전이었는데 수정 중에 잠금 시각(오픈 3분 전)이 지나면 커밋하지 않는다 — 409, 아무것도 안 남는다")
    void openingDuringTheEditRejectsTheCommit() throws Exception {
        UUID productId = registerPreorder();
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(1)));

        AtomicInteger reads = new AtomicInteger();
        Instant freezesAt = opensAt.minus(ProductEditService.FREEZE_BEFORE_OPEN);
        CLOCK.next = () -> reads.getAndIncrement() == 0 ? freezesAt.minusMillis(1) : freezesAt;   // 첫 판정 뒤 시간이 잠금 시각을 넘는다
        assertStateConflict(() -> editService.editProduct(productId, titleOnly("오픈을 넘긴 수정")));
        assertThat(reads.get()).as("시계를 두 번 이상 읽었다 — 첫 판정은 통과했다").isGreaterThanOrEqualTo(2);
        assertThat(title(productId)).isEqualTo("Race");
    }

    @Test
    @DisplayName("옵션 판매 상태를 바꾸는 중에 잠금 시각이 지나도 커밋하지 않는다 — 커밋 직전에 다시 본다")
    void openingDuringTheOptionStatusChangeRejectsTheCommit() throws Exception {
        UUID productId = registerPreorder();
        UUID optionId = optionId(productId, "256GB");
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(1)));

        AtomicInteger reads = new AtomicInteger();
        Instant freezesAt = opensAt.minus(ProductEditService.FREEZE_BEFORE_OPEN);
        CLOCK.next = () -> reads.getAndIncrement() == 0 ? freezesAt.minusMillis(1) : freezesAt;
        assertStateConflict(() -> editService.editVariant(productId, optionId, new VariantEditRequest(SaleStatus.PAUSED)));
        assertThat(reads.get()).as("첫 판정은 통과했다").isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM product_options WHERE id = ?", String.class, UuidBinary.toBytes(optionId)))
                .as("상태 그대로").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("수정 중에 preorder 가 회차 오픈을 앞당겨 이미 잠금 시각이 지났으면 커밋하지 않는다 — 커밋 직전 판정은 그때까지 커밋된 회차를 본다")
    void campaignMovedEarlierDuringTheEditRejectsTheCommit() throws Exception {
        UUID productId = registerPreorder();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, now.plus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)));

        AtomicInteger reads = new AtomicInteger();
        CLOCK.next = () -> {
            if (reads.getAndIncrement() == 0) {
                // 첫 판정 순간, 다른 커넥션(preorder)이 오픈을 1분 전으로 옮겨 커밋한다. 회차를 새로 넣는 것은 수정 중에 못 한다 —
                // preorder_campaigns → products 외래키 확인이 수정이 잡은 상품 행 잠금을 기다린다(실측: 30초 대기 뒤 수정이 먼저 커밋)
                onAnotherConnection(() -> fixtures.moveCampaignOpensAt(productId, now.minus(Duration.ofMinutes(1))));
            }
            return now;
        };
        assertStateConflict(() -> editService.editProduct(productId, titleOnly("회차가 옮겨진 수정")));
        assertThat(reads.get()).as("첫 판정은 옮기기 전 회차로 통과했다").isGreaterThanOrEqualTo(2);
        assertThat(title(productId)).isEqualTo("Race");
    }

    @Test
    @DisplayName("정보 수정이 커밋되기 전에 판매 상태 전환이 들어오면 기다렸다가 새 정보 위에 적는다 — 제목도 상태도 남는다")
    void saleStatusChangeSerializesWithEdit() throws Exception {
        UUID productId = registerInStock();
        interleave(() -> editService.editProduct(productId, titleOnly("먼저 고친 제목")),
                () -> editService.changeSaleStatus(productId, new SaleStatusChangeRequest(SaleStatus.PAUSED)));

        assertThat(title(productId)).as("잠그지 않고 읽은 상태 전환이 옛 제목을 다시 쓰면 Race").isEqualTo("먼저 고친 제목");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM products WHERE id = ?", String.class, UuidBinary.toBytes(productId))).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("기본가 수정이 커밋되기 전에 공개 전환이 들어오면 기다렸다가 새 기본가 위에 적는다 — 기본가 · 옵션 가격 · 공개 여부가 모두 새 값")
    void visibilityChangeSerializesWithEdit() throws Exception {
        UUID productId = registerInStock();
        interleave(() -> editService.editProduct(productId, new ProductEditRequest(null, null, null, new BigDecimal("1100000"), null)),
                () -> editService.changeVisibility(productId, new VisibilityChangeRequest(true)));

        assertThat(jdbcTemplate.queryForObject("SELECT base_price FROM products WHERE id = ?", BigDecimal.class, UuidBinary.toBytes(productId)))
                .as("옛 기본가로 덮이면 옵션 가격(새 기본가로 계산)과 어긋난다").isEqualByComparingTo("1100000");
        assertThat(price(productId, "256GB")).isEqualByComparingTo("1100000");
        assertThat(jdbcTemplate.queryForObject("SELECT visible FROM products WHERE id = ?", Boolean.class, UuidBinary.toBytes(productId))).isTrue();
    }

    @Test
    @DisplayName("같은 판매 중지 · 같은 공개 전환이 겹쳐 와도 PREORDER_PRODUCT_CHANGED 는 하나씩 — 뒤의 것은 잠금을 기다렸다가 이미 바뀐 값을 보고 적지 않는다")
    void identicalConcurrentTransitionsNotifyOnce() throws Exception {
        UUID productId = registerPreorder();
        interleave(() -> editService.changeSaleStatus(productId, new SaleStatusChangeRequest(SaleStatus.PAUSED)),
                () -> editService.changeSaleStatus(productId, new SaleStatusChangeRequest(SaleStatus.PAUSED)));
        assertThat(changedEvents(productId)).isEqualTo(1);

        interleave(() -> editService.changeVisibility(productId, new VisibilityChangeRequest(true)),   // 등록 때 비공개
                () -> editService.changeVisibility(productId, new VisibilityChangeRequest(true)));
        assertThat(changedEvents(productId)).isEqualTo(2);
    }

    @Test
    @DisplayName("판매 상태 전환 중에 잠금 시각(오픈 3분 전)이 지나면 커밋하지 않는다 — 409, 상태 그대로")
    void openingDuringTheSaleStatusChangeRejectsTheCommit() throws Exception {
        UUID productId = registerPreorder();
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(1)));

        AtomicInteger reads = new AtomicInteger();
        Instant freezesAt = opensAt.minus(ProductEditService.FREEZE_BEFORE_OPEN);
        CLOCK.next = () -> reads.getAndIncrement() == 0 ? freezesAt.minusMillis(1) : freezesAt;
        assertStateConflict(() -> editService.changeSaleStatus(productId, new SaleStatusChangeRequest(SaleStatus.PAUSED)));
        assertThat(reads.get()).as("첫 판정은 통과했다").isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM products WHERE id = ?", String.class, UuidBinary.toBytes(productId))).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("판매 상태 전환 중에 preorder 가 회차 오픈을 앞당겨 잠금 시각이 지났으면 커밋하지 않는다 — 409, 상태 그대로")
    void campaignMovedEarlierDuringTheSaleStatusChangeRejectsTheCommit() throws Exception {
        UUID productId = registerPreorder();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, now.plus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)));

        AtomicInteger reads = new AtomicInteger();
        CLOCK.next = () -> {
            if (reads.getAndIncrement() == 0) {
                onAnotherConnection(() -> fixtures.moveCampaignOpensAt(productId, now.minus(Duration.ofMinutes(1))));
            }
            return now;
        };
        assertStateConflict(() -> editService.changeSaleStatus(productId, new SaleStatusChangeRequest(SaleStatus.PAUSED)));
        assertThat(reads.get()).as("첫 판정은 옮기기 전 회차로 통과했다").isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM products WHERE id = ?", String.class, UuidBinary.toBytes(productId))).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("오픈 그 순간(지금 == opens_at)부터 회차 취소다 — 사유와 함께 판매 중지를 보내면 취소 접수. 1마이크로초 전은 잠금 구간이라 409")
    void campaignCancelStartsAtOpensAt() throws Exception {
        UUID productId = registerPreorder();
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(1)));

        CLOCK.next = () -> opensAt.minus(1, ChronoUnit.MICROS);
        assertStateConflict(() -> editService.changeSaleStatus(productId, new SaleStatusChangeRequest(SaleStatus.PAUSED, "직전")));
        CLOCK.next = () -> opensAt;
        assertThat(editService.changeSaleStatus(productId, new SaleStatusChangeRequest(SaleStatus.PAUSED, "그 순간")).campaignCancellationRequested())
                .isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT campaign_canceled_at IS NOT NULL FROM products WHERE id = ?", Boolean.class, UuidBinary.toBytes(productId)))
                .isTrue();
    }

    @Test
    @DisplayName("오픈 뒤 회차 취소가 동시에 두 번 들어와도 취소 이벤트는 하나다 — 뒤의 것은 앞의 취소가 커밋된 뒤 취소 표식을 본다")
    void concurrentCampaignCancelsWriteOneEvent() throws Exception {
        UUID productId = registerPreorder();
        Instant now = Instant.now();
        fixtures.campaign(productId, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        interleave(() -> editService.changeSaleStatus(productId, new SaleStatusChangeRequest(SaleStatus.PAUSED, "첫 취소")),
                () -> editService.changeSaleStatus(productId, new SaleStatusChangeRequest(SaleStatus.PAUSED, "겹친 취소")));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM catalog_outbox_events WHERE aggregate_id = ? AND event_type = 'PREORDER_CAMPAIGN_CANCELED'
                """, Integer.class, UuidBinary.toBytes(productId))).as("잠그지 않고 읽으면 둘 다 취소 전으로 보고 이벤트를 둘 적는다").isEqualTo(1);
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

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

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    private UUID optionId(UUID productId, String title) {
        return UuidBinary.fromBytes(jdbcTemplate.queryForObject("SELECT id FROM product_options WHERE product_id = ? AND title = ?",
                byte[].class, UuidBinary.toBytes(productId), title));
    }

    /** member 소유 표 — 장바구니 외래키를 채우려고 시험 데이터로만 넣는다. */
    private UUID customer() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO customers (id, kakao_id, display_name, created_at, updated_at) VALUES (?, ?, 'race', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                UuidBinary.toBytes(id), "k-" + ShopFixtures.unique());
        return id;
    }

    /** order 소유 표 — 다른 모듈의 쓰기를 흉내 낸다(외래키 확인이 옵션 행에 공유 잠금). */
    private void cartItem(UUID customerId, UUID optionId) {
        jdbcTemplate.update("INSERT INTO cart_items (id, customer_id, option_id, quantity, created_at, updated_at) VALUES (?, ?, ?, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                UuidBinary.toBytes(UUID.randomUUID()), UuidBinary.toBytes(customerId), UuidBinary.toBytes(optionId));
    }

    private static ProductEditRequest titleOnly(String title) {
        return new ProductEditRequest(title, null, null, null, null);
    }

    private static void assertStateConflict(Runnable edit) {
        assertThatThrownBy(edit::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CatalogErrorCode.STATE_CONFLICT));
    }

    /**
     * 둘째 작업이 상품 행 잠금(products … FOR UPDATE)에서 기다리는 중이거나 이미 끝났을 때까지 기다린다. 같은 사용자의 스레드는 PROCESS 권한 없이
     * PROCESSLIST 에 보이고(performance_schema 의 잠금 대기 표는 시험 계정으로 못 읽는다 — 실측), 쉬는 커넥션은 COMMAND=Sleep 이다.
     * 그 잠금 조회가 두 번 연속(약 20ms 간격) 보일 때 넘어간다 — 아무 실행 중 쿼리나 보고 넘어가면 둘째 작업이 잠금에 닿기 전에 첫째를 풀어
     * 직렬 실행이 되고, 직렬로도 통과하는 단언이 잠금 결함을 놓친다.
     */
    private void awaitBlockedOrDone(Future<?> second) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int seen = 0;
        while (System.nanoTime() < deadline) {
            if (second.isDone()) {
                return;
            }
            Long blocked = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.PROCESSLIST
                     WHERE COMMAND = 'Query' AND ID <> CONNECTION_ID() AND LOWER(INFO) LIKE '%from products %for update%'
                    """, Long.class);
            seen = blocked != null && blocked > 0 ? seen + 1 : 0;
            if (seen >= 2) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("둘째 작업이 30초 안에 상품 행 잠금에서 기다리지도 끝나지도 않았다");
    }

    /**
     * 수정이 옵션 행 UPDATE 에서 기다릴 때까지(생성 순으로 A 를 잡고 B 를 기다리는 중) 또는 끝날 때까지 기다린다.
     * "다른 연결의 아무 실행 중 쿼리" 를 신호로 삼으면 수정이 아직 상품 행 잠금 · 앞쪽 조회에 있을 때도 넘어가,
     * 장바구니가 A 를 먼저 넣고 교착 없이 지나갈 수 있다 — 교착 시험이 가끔 실패하던 가능한 원인이다(실측: 다른 트랜잭션이 상품 행을 1초 잡아
     * 수정을 그 단계에 붙잡아 두면 매번 그렇게 실패한다. 자연 조건에서는 재현되지 않았고, 처음 기록된 실패의 메시지는 남아 있지 않다).
     * 옵션 UPDATE 가 두 번 연속(약 20ms 간격) 보일 때 넘어간다 — A 의 대기와 B 의 대기를 가르지는 못하지만, 이 시험에서는 A 를 잡는 다른 트랜잭션이 없다.
     */
    private void awaitOptionUpdateBlockedOrDone(Future<?> edit) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int seen = 0;
        while (System.nanoTime() < deadline) {
            if (edit.isDone()) {
                return;
            }
            Long blocked = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.PROCESSLIST
                     WHERE COMMAND = 'Query' AND ID <> CONNECTION_ID() AND LOWER(INFO) LIKE 'update product_options%'
                    """, Long.class);
            seen = blocked != null && blocked > 0 ? seen + 1 : 0;
            if (seen >= 2) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("수정이 30초 안에 옵션 UPDATE 에서 기다리지도 끝나지도 않았다");
    }

    /** 다른 커넥션(자동 커밋)에서 실행하고 끝날 때까지 기다린다 — 다른 모듈의 쓰기를 흉내 낸다. */
    private static void onAnotherConnection(Runnable write) {
        // 쓰기가 실패하거나 끝나지 않으면 그 자리에서 실패한다 — 전제(회차가 옮겨졌다) 없이 뒤 단언이 엉뚱하게 깨지지 않게
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                write.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        writer.start();
        try {
            writer.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (writer.isAlive()) {
            throw new AssertionError("다른 커넥션의 쓰기가 30초 안에 끝나지 않았다(잠금 대기)");
        }
        if (failure.get() != null) {
            throw new AssertionError("다른 커넥션의 쓰기가 실패했다", failure.get());
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

    private BigDecimal price(UUID productId, String storage) {
        return jdbcTemplate.queryForObject("SELECT price FROM product_options WHERE product_id = ? AND title = ?",
                BigDecimal.class, UuidBinary.toBytes(productId), storage);
    }

    private String optionStatus(UUID optionId) {
        return jdbcTemplate.queryForObject("SELECT status FROM product_options WHERE id = ?", String.class, UuidBinary.toBytes(optionId));
    }

    private String title(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT title FROM products WHERE id = ?", String.class, UuidBinary.toBytes(productId));
    }

    private long changedEvents(UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_outbox_events WHERE aggregate_id = ? AND event_type = 'PREORDER_PRODUCT_CHANGED'", Long.class, UuidBinary.toBytes(productId));
    }

    /** 축 storage 하나(256GB +0 · 512GB +200,000), 기본가 1,000,000. */
    private UUID registerInStock() throws Exception {
        return register("""
                { "categoryId": "%s", "saleMode": "IN_STOCK", "title": "Race", "visible": false, "basePrice": 1000000,
                  "optionAxes": [ { "key": "storage", "label": "용량", "values": [ { "value": "256GB" }, { "value": "512GB", "surcharge": 200000 } ] } ],
                  "combinations": [ { "selections": { "storage": "256GB" }, "stock": 1 }, { "selections": { "storage": "512GB" }, "stock": 1 } ] }
                """.formatted(categoryId));
    }

    private UUID registerPreorder() throws Exception {
        Instant opensAt = Instant.now().plus(Duration.ofHours(2));
        return register("""
                { "categoryId": "%s", "saleMode": "PREORDER", "title": "Race", "visible": false, "basePrice": 1000000,
                  "optionAxes": [ { "key": "storage", "label": "용량", "values": [ { "value": "256GB" } ] } ],
                  "combinations": [ { "selections": { "storage": "256GB" } } ],
                  "campaign": { "opensAt": "%s", "closesAt": "%s" },
                  "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ] }
                """.formatted(categoryId, opensAt, opensAt.plus(Duration.ofDays(3))));
    }

    private UUID register(String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON)
                        .with(user("admin").roles("ADMIN")).header("Idempotency-Key", "k-" + ShopFixtures.unique()).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(response).get("data").get("registration").get("productId").asString());
    }

    /** 평소엔 앱과 같은 시계(마이크로초 해상도의 UTC). 시험이 next 를 채우면 그 값을 낸다. */
    static final class ControllableClock extends Clock {

        private final Clock system = StorageClock.atStorageResolution(Clock.systemUTC());
        volatile Supplier<Instant> next;
        /**
         * next 를 받는 스레드(시험 스레드). 앱의 다른 스레드 — 커밋 직후 아웃박스 발행(outbox-publish-*) 등 — 도 같은 시계를 읽는데,
         * 앞 시험이 남긴 발행이 다음 시험의 "첫 읽기" 를 먼저 가져가면 판정 순서가 틀어진다(실측: 회차 취소 뒤 시계를 읽은 것은 outbox-publish-2).
         * 그래서 next 는 이 스레드에만 주고 나머지는 실제 시계를 읽는다.
         */
        volatile Thread owner;

        @Override
        public Instant instant() {
            Supplier<Instant> supplier = next;
            return supplier == null || Thread.currentThread() != owner ? system.instant() : supplier.get();
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
