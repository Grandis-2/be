package com.grandis.nova.catalog.migration;

import com.grandis.nova.catalog.option.ProductOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보증 칸 → products.options 의 warranty 옮기기(V202610080113)와 칸 삭제(V202610080114). 앞 버전까지 올린 빈 DB 에 칸으로 보증을 넣고
 * 옮긴 뒤 문서를 앱의 읽기 규칙({@link ProductOptions})으로 대조한다. 삭제 전 다시 채우기가 "구버전이 문서를 다시 써 키가 빠진 행" 만
 * 칸에서 채우는지도 본다. 팀 Flyway CLI 와 같은 연결 콜레이션으로 돌린다(OptionsBackfillMigrationTest 와 같은 이유).
 */
class WarrantyMigrationTest {

    static final String BEFORE = "202610072049";
    static final String MOVE = "202610080113";
    /** 보증 칸을 지우는 버전. 최신까지 올리면 뒤의 마이그레이션(id 형 전환)이 표를 비워 남은 단정을 볼 수 없다. */
    static final String DROP = "202610080114";

    MySQLContainer mysql;
    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;

    @BeforeEach
    void start() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("shop");
        mysql.start();
        // 한 커넥션 — LAST_INSERT_ID 는 커넥션마다다
        dataSource = new SingleConnectionDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void stop() {
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    @DisplayName("보증 칸을 문서로 옮기고(제공 안 하면 추가금 0), 삭제 전에는 키가 빠진 행만 칸에서 다시 채운 뒤 칸을 지운다")
    void movesWarrantyIntoDocumentAndDropsColumns() {
        migrate(BEFORE);
        long category = insert("INSERT INTO categories (name, created_at, updated_at) VALUES ('폰', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        long offered = product(category, 1, "199000");
        long notOffered = product(category, 0, "0");
        jdbc.update("UPDATE products SET options = JSON_OBJECT('axes', JSON_ARRAY(JSON_OBJECT('key', 'color', 'label', '색상', 'values', JSON_ARRAY()))) WHERE id = ?",
                offered);

        migrate(MOVE);

        assertThat(warranty(offered)).isEqualTo(new ProductOptions.Warranty(true, new BigDecimal("199000")));
        assertThat(document(offered).axes()).as("다른 내용은 그대로").singleElement().satisfies(axis -> assertThat(axis.key()).isEqualTo("color"));
        assertThat(warranty(notOffered)).isEqualTo(ProductOptions.Warranty.NONE);
        assertThat(jdbc.queryForObject("SELECT JSON_TYPE(options->'$.warranty.surcharge') FROM products WHERE id = ?", String.class, offered))
                .as("숫자로 옮긴다(문자열이 아니다)").isIn("INTEGER", "DECIMAL");

        // 동결 창 안에서 구버전이 한 일: 문서를 다시 써 키를 지우고(옵션 수정) 칸으로 보증을 바꿨다
        jdbc.update("UPDATE products SET options = JSON_REMOVE(options, '$.warranty'), warranty_offered = 1, warranty_surcharge = 50000 WHERE id = ?",
                notOffered);
        // 새 버전이 한 일: 문서의 보증을 바꿨다 — 칸은 그대로라 갈린다. 이건 덮지 않는다
        jdbc.update("UPDATE products SET options = JSON_SET(options, '$.warranty.surcharge', 120000) WHERE id = ?", offered);

        migrate(DROP);

        assertThat(warranty(notOffered)).as("키가 빠진 행은 칸에서 다시 채운다").isEqualTo(new ProductOptions.Warranty(true, new BigDecimal("50000")));
        assertThat(warranty(offered)).as("키가 있는 행은 문서가 정본 — 칸으로 되돌리지 않는다").isEqualTo(new ProductOptions.Warranty(true, new BigDecimal("120000")));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = 'shop' AND table_name = 'products' AND column_name IN ('warranty_offered', 'warranty_surcharge')
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE table_schema = 'shop' AND constraint_name = 'ck_product_warranty_surcharge'
                """, Long.class)).as("칸 하나만 보던 CHECK 는 칸과 함께 사라진다").isZero();
    }

    private void migrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "connectionCollation=utf8mb4_unicode_ci",
                        "root", mysql.getPassword())
                .locations("filesystem:" + System.getProperty("nova.migrations-path"))
                .schemas("shop").defaultSchema("shop")
                .createSchemas(false).cleanDisabled(true).validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private long product(long category, int warrantyOffered, String surcharge) {
        return insert("""
                INSERT INTO products (category_id, sale_mode, title, status, warranty_offered, warranty_surcharge, created_at, updated_at)
                VALUES (%d, 'IN_STOCK', 'p', 'ACTIVE', %d, %s, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""".formatted(category, warrantyOffered, surcharge));
    }

    private long insert(String sql) {
        jdbc.update(sql);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private ProductOptions document(long productId) {
        return ProductOptions.parse(jdbc.queryForObject("SELECT options FROM products WHERE id = ?", String.class, productId));
    }

    private ProductOptions.Warranty warranty(long productId) {
        return document(productId).warranty();
    }
}
