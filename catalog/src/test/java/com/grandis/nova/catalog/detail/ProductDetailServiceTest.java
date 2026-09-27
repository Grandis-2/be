package com.grandis.nova.catalog.detail;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.catalog.support.SqlHookInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

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
        Long productId = fixtures.product(fixtures.category(), "IN_STOCK", "ACTIVE", "스냅샷", null);
        fixtures.completeRegistration(productId);
        Long option = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        fixtures.inventory(option, 5, 0, 0);
        boolean[] hookRan = {false};
        SqlHookInspector.before("product_options", () -> {
            commitOnAnotherConnection("UPDATE product_options SET price = 2000 WHERE id = " + option);
            commitOnAnotherConnection("UPDATE option_inventories SET stock_sold = 5 WHERE option_id = " + option);
            hookRan[0] = true;
        });

        ProductDetailView view = service.findProduct(productId, false);

        assertThat(hookRan[0]).as("훅이 첫 문장 뒤 · 옵션 문장 앞에서 돌았다").isTrue();
        assertThat(view.variants().getFirst().price()).isEqualByComparingTo("1000");
        assertThat(view.variants().getFirst().availableQuantity()).isEqualTo(5);
        assertThat(view.soldOut()).isFalse();
        // 트랜잭션이 끝난 뒤에는 새 값이 보인다 — 대조군
        ProductDetailView after = service.findProduct(productId, false);
        assertThat(after.variants().getFirst().price()).isEqualByComparingTo("2000");
        assertThat(after.soldOut()).isTrue();
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
