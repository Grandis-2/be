package com.grandis.nova.catalog;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * products.stock_ready_at 기존 상품 채우기 마이그레이션. 빈 DB 에서는 고칠 행이 없어 Flyway 실행으로는 재지 못한다 — 행을 넣고 그
 * 마이그레이션 파일의 UPDATE 문장을 그대로 다시 돌린다.
 */
@CatalogIntegrationTest
class StockReadyBackfillTest {

    static final String MIGRATION = "V202610190156__catalog_product_stock_ready_fill.sql";

    @Autowired JdbcTemplate jdbcTemplate;
    @Value("${nova.migrations-path}") String migrations;

    @Test
    @DisplayName("재고 행이 있는 일반 상품만 채운다 — 재고 행 없는 일반 상품 · 사전예약 상품은 비워 두고, 이미 채운 상품의 시각은 그대로")
    void fillsInStockProductsWithInventoryRows() throws Exception {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        UUID stocked = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.inventoryOnly(fixtures.option(stocked, "ACTIVE", ShopFixtures.OPTION_PRICE), 1, 0, 0);
        UUID noRows = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.option(noRows, "ACTIVE", ShopFixtures.OPTION_PRICE);
        UUID preorder = fixtures.product("PREORDER", "ACTIVE");
        fixtures.inventoryOnly(fixtures.option(preorder, "ACTIVE", ShopFixtures.OPTION_PRICE), 1, 0, 0);
        UUID already = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.inventoryOnly(fixtures.option(already, "ACTIVE", ShopFixtures.OPTION_PRICE), 1, 0, 0);
        jdbcTemplate.update("UPDATE products SET stock_ready_at = '2026-01-01 00:00:00.000000' WHERE id = ?", (Object) UuidBinary.toBytes(already));

        jdbcTemplate.update(backfill());

        assertThat(readyAt(stocked)).isNotNull();
        assertThat(readyAt(noRows)).isNull();
        assertThat(readyAt(preorder)).isNull();
        assertThat(readyAt(already)).hasToString("2026-01-01 00:00:00.0");
    }

    /** 채우기 마이그레이션의 UPDATE 문장. 스키마 이름(shop.)은 시험 연결의 기본 스키마와 같다. */
    private String backfill() throws Exception {
        String sql = Files.readString(Path.of(migrations, MIGRATION));
        int start = sql.indexOf("UPDATE shop.products");
        assertThat(start).as("채우기 문장").isPositive();
        return sql.substring(start, sql.indexOf(';', start));
    }

    private Timestamp readyAt(UUID product) {
        return jdbcTemplate.queryForObject("SELECT stock_ready_at FROM products WHERE id = ?", Timestamp.class, (Object) UuidBinary.toBytes(product));
    }
}
