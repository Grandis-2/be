package com.grandis.nova.catalog.product;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@CatalogIntegrationTest
class DiscountSqlTest {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("목록 SQL 의 할인식은 Java 계산과 같다 — 옵션 가격 칼럼(decimal(12,0)) · 할인율 칼럼(int) 위에서 잰다")
    void sqlMatchesJava() {
        UUID productId = new ShopFixtures(jdbcTemplate).product("IN_STOCK", "ACTIVE");
        List<BigDecimal> prices = List.of(BigDecimal.ZERO, new BigDecimal("1"), new BigDecimal("99"), new BigDecimal("150"),
                new BigDecimal("2278100"), new BigDecimal("2278190"), new BigDecimal("999999999999"));
        String sql = "SELECT " + Discount.sql("o.price", "p.discount_rate")
                + " FROM products p JOIN product_options o ON o.product_id = p.id WHERE o.id = ?";
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        for (BigDecimal price : prices) {
            UUID optionId = fixtures.option(productId, "ACTIVE", price);
            for (int rate : new int[] {0, 1, 5, 7, 33, 50, 99}) {
                jdbcTemplate.update("UPDATE products SET discount_rate = ? WHERE id = ?", rate, UuidBinary.toBytes(productId));
                BigDecimal fromSql = jdbcTemplate.queryForObject(sql, BigDecimal.class, UuidBinary.toBytes(optionId));
                assertThat(fromSql).as("price=%s rate=%d", price, rate).isEqualByComparingTo(Discount.apply(price, rate));
            }
        }
    }

    @Test
    @DisplayName("할인율 칼럼은 0~99 만 담고, 칸을 빼고 넣은 행(다른 모듈 픽스처)은 0 이다")
    void columnHoldsZeroToNinetyNine() {
        UUID productId = new ShopFixtures(jdbcTemplate).product("IN_STOCK", "ACTIVE");
        byte[] id = UuidBinary.toBytes(productId);
        assertThat(jdbcTemplate.queryForObject("SELECT discount_rate FROM products WHERE id = ?", Integer.class, (Object) id)).isZero();
        jdbcTemplate.update("UPDATE products SET discount_rate = 99 WHERE id = ?", (Object) id);
        for (int rate : new int[] {-1, 100}) {
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE products SET discount_rate = ? WHERE id = ?", rate, id))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_product_discount_rate");
        }
    }
}
