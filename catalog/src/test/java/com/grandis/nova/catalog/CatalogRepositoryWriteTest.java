package com.grandis.nova.catalog;

import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리포지터리(JPA) 경로로 썼을 때도 DB 제약에 닿는지. 스키마 시험은 SQL 로 직접 치므로 이 경로는 따로 잰다 —
 * 서비스가 기대는 것은 save() 의 INSERT 가 UNIQUE 위반을 예외로 돌려주는 것이라, 트랜잭션 밖에서 저장소로 두 번 저장해 예외와 남은 행을 단언한다.
 */
@CatalogIntegrationTest
class CatalogRepositoryWriteTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductRepository products;
    @Autowired ProductOptionRepository options;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("같은 멱등 키로 상품을 두 번 저장하면 DB 가 거절하고 처음 상품이 남는다")
    void secondProductWithSameIdempotencyKeyIsRejected() {
        String key = ShopFixtures.unique();
        Long categoryId = fixtures.category();
        Product first = products.saveAndFlush(Product.register(key, categoryId, SaleMode.PREORDER, "First",
                BigDecimal.ONE, null, null, false, false, BigDecimal.ZERO, ProductOptions.EMPTY));

        assertThatThrownBy(() -> products.saveAndFlush(Product.register(key, categoryId, SaleMode.PREORDER, "Second",
                BigDecimal.ONE, null, null, false, false, BigDecimal.ZERO, ProductOptions.EMPTY)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_product_idempotency");

        assertThat(jdbcTemplate.queryForList("SELECT title FROM products WHERE idempotency_key = ?", String.class, key))
                .containsExactly("First");
        assertThat(products.findByIdempotencyKey(key)).get().extracting(Product::getId).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("축이 없는 상품의 옵션은 두 번째 저장을 DB 가 거절한다 — 조회 뒤 INSERT 경합에 기대지 않는다")
    void secondStandaloneOptionIsRejected() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        options.saveAndFlush(ProductOption.of("ONLY-1", new BigDecimal("1000"), OptionCombination.none(productId, "Nova 1")));
        assertThatThrownBy(() -> options.saveAndFlush(
                ProductOption.of("ONLY-2", new BigDecimal("1000"), OptionCombination.none(productId, "Nova 1"))))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_option_combination");
        // catalog 밖에서 키 없이 넣은 행은 계속 여럿이어도 된다
        fixtures.option(productId, "ACTIVE");
        fixtures.option(productId, "ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_options WHERE product_id = ?", Long.class, productId))
                .isEqualTo(3L);
    }
}
