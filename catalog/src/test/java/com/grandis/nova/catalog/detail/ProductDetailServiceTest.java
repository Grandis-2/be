package com.grandis.nova.catalog.detail;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.catalog.support.SqlHookInspector;
import com.grandis.nova.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상세는 여러 문장을 읽는다. 첫 문장 뒤에 다른 커넥션이 가격과 재고를 바꿔 커밋해도 응답은 전부 이전 값이어야 한다 —
 * REPEATABLE READ 한 스냅샷. READ COMMITTED 면 옵션 문장은 새 가격을 읽어 상품과 옵션이 다른 시점이 된다(실측).
 */
@CatalogIntegrationTest
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.grandis.nova.catalog.support.SqlHookInspector")
class ProductDetailServiceTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductDetailService service;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        SqlHookInspector.reset();
    }

    @AfterEach
    void tearDown() {
        SqlHookInspector.reset();
    }

    @Test
    @DisplayName("상세를 읽는 도중 커밋된 가격 · 재고 변경은 이번 응답에 섞이지 않는다")
    void detailReadsOneSnapshot() {
        UUID productId = fixtures.product(fixtures.category(), "IN_STOCK", "ACTIVE", "스냅샷", null);
        fixtures.registration(productId);
        UUID option = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        fixtures.inventory(option, 5, 0, 0);
        boolean[] hookRan = {false};
        SqlHookInspector.before("product_options", () -> {
            commitOnAnotherConnection("UPDATE product_options SET price = 2000 WHERE id = UUID_TO_BIN('" + option + "')");
            commitOnAnotherConnection("UPDATE option_inventories SET stock_sold = 5 WHERE option_id = UUID_TO_BIN('" + option + "')");
            hookRan[0] = true;
        });

        ProductDetailView view = service.findProduct(productId);

        assertThat(hookRan[0]).as("훅이 첫 문장 뒤 · 옵션 문장 앞에서 돌았다").isTrue();
        assertThat(view.variants().getFirst().price()).isEqualByComparingTo("1000");
        assertThat(view.variants().getFirst().availableQuantity()).isEqualTo(5);
        assertThat(view.soldOut()).isFalse();
        // 트랜잭션이 끝난 뒤에는 새 값이 보인다 — 대조군
        ProductDetailView after = service.findProduct(productId);
        assertThat(after.variants().getFirst().price()).isEqualByComparingTo("2000");
        assertThat(after.soldOut()).isTrue();
    }

    @Test
    @DisplayName("관리자 상세를 읽는 도중 커밋된 가격 변경도 이번 응답에 섞이지 않는다 — 회원 상세와 같은 REPEATABLE READ")
    void adminDetailReadsOneSnapshot() {
        UUID productId = fixtures.product(fixtures.category(), "IN_STOCK", "ACTIVE", "관리자 스냅샷", null);
        UUID option = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        boolean[] hookRan = {false};
        SqlHookInspector.before("product_options", () -> {
            commitOnAnotherConnection("UPDATE product_options SET price = 2000 WHERE id = UUID_TO_BIN('" + option + "')");
            hookRan[0] = true;
        });

        AdminProductDetail detail = service.findAdminProduct(productId);

        assertThat(hookRan[0]).isTrue();
        assertThat(detail.product().variants().getFirst().price()).isEqualByComparingTo("1000");
        assertThat(service.findAdminProduct(productId).product().variants().getFirst().price()).as("대조군").isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("관리자 상세의 등록 완료는 판매 방식별 준비다 — 사전예약은 회차 행이 생기면 true, visible 은 칸 그대로")
    void adminDetailCompletionFollowsReadiness() {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        String key = ShopFixtures.unique();
        fixtures.registration(productId, key);

        AdminProductDetail before = service.findAdminProduct(productId);
        assertThat(before.registrationKey()).isEqualTo(key);
        assertThat(before.registrationCompleted()).as("회차 행 전").isFalse();
        assertThat(before.product().visible()).as("관리자 상세의 visible 은 칸 그대로(픽스처 기본값 1) — 준비 여부와 섞지 않는다").isTrue();

        fixtures.campaign(productId, Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(2)));
        assertThat(service.findAdminProduct(productId).registrationCompleted()).as("회차 행 뒤").isTrue();
    }

    @Test
    @DisplayName("일반 상품은 그 상품 옵션의 재고 행이 생겨야 준비다 — 다른 상품의 재고 행은 세지 않는다")
    void inStockReadinessCountsOnlyItsOwnOptions() {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        UUID option = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        UUID other = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.inventory(fixtures.option(other, "ACTIVE", new BigDecimal("1000")), 3, 0, 0);
        fixtures.registration(productId);
        assertThat(service.findAdminProduct(productId).registrationCompleted()).isFalse();

        fixtures.inventory(option, 0, 0, 0);
        assertThat(service.findAdminProduct(productId).registrationCompleted()).as("재고 0 이어도 행이 있으면 준비").isTrue();
    }

    @Test
    @DisplayName("회원 상세 · 옵션 상세는 공개여도 준비 전이면 404 다")
    void memberDetailHidesProductsNotReady() {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        UUID option = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        fixtures.registration(productId);
        assertThatThrownBy(() -> service.findProduct(productId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.findVariant(productId, option)).isInstanceOf(BusinessException.class);

        fixtures.inventory(option, 1, 0, 0);
        assertThat(service.findProduct(productId).productId()).isEqualTo(productId);
        assertThat(service.findVariant(productId, option).variantId()).isEqualTo(option);
    }

    private void commitOnAnotherConnection(String sql) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> jdbcTemplate.update(sql)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
