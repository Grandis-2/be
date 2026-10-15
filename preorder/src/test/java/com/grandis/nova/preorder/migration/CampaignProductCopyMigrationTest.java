package com.grandis.nova.preorder.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차 상품 사본 채우기(V202610151211). 앞 버전까지 올린 DB 의 회차를 catalog 상품 값으로 한 번 채우고,
 * 공개 여부가 실제와 달랐던 회차만 일정 번호를 올린다(배포 뒤 재발행으로 대기열이 새 값을 받게).
 */
class CampaignProductCopyMigrationTest {

    static final String BEFORE = "202610151210";
    static final String COPY = "202610151211";

    MySQLContainer mysql;
    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;

    @BeforeEach
    void start() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("shop");
        mysql.start();
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
    @DisplayName("기존 회차에 상품명 · 판매 상태 · 공개 여부를 채우고, 공개 여부가 바뀐 회차만 일정 번호를 올린다. 변경 번호는 0 그대로")
    void copiesListingAndBumpsScheduleOnlyWhenVisibilityChanged() {
        flyway(BEFORE).migrate();
        UUID hidden = campaignOf(product("숨긴 폰", "PAUSED", false));
        UUID shown = campaignOf(product("보인 폰", "ACTIVE", true));

        flyway(COPY).migrate();

        assertThat(row(hidden)).isEqualTo("숨긴 폰|PAUSED|0|0|2");
        assertThat(row(shown)).isEqualTo("보인 폰|ACTIVE|1|0|1");
    }

    /** 부모 행(카테고리)은 이 시험의 관심이 아니라 외래 키 검사를 끄고 넣는다. 같은 연결이라 세션 설정이 이어진다. */
    private UUID product(String title, String status, boolean visible) {
        UUID id = UUID.randomUUID();
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.update("""
                INSERT INTO products (id, category_id, sale_mode, title, brand, model_name, status, visible, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(UUID()), 'PREORDER', ?, 'APPLE', 'A0001', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id.toString(), title, status, visible);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        return id;
    }

    /** 지금까지 catalog 가 공개 여부를 보낸 적이 없어 회차는 모두 공개 · 번호 0 · 일정 번호 1 이다. */
    private UUID campaignOf(UUID productId) {
        jdbc.update("""
                INSERT INTO preorder_campaigns (product_id, opens_at, closes_at, schedule_version, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), '2026-11-01 00:00:00', '2026-11-02 00:00:00', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId.toString());
        return productId;
    }

    /** 상품명 | 판매 상태 | 공개 여부 | 변경 번호 | 일정 번호 */
    private String row(UUID productId) {
        return jdbc.queryForObject("""
                SELECT CONCAT_WS('|', product_title, product_status, visible, visibility_version, schedule_version)
                  FROM preorder_campaigns WHERE product_id = UUID_TO_BIN(?)
                """, String.class, productId.toString());
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "connectionCollation=utf8mb4_unicode_ci",
                        "root", mysql.getPassword())
                .locations("filesystem:" + System.getProperty("nova.migrations-path"))
                .schemas("shop").defaultSchema("shop")
                .createSchemas(false).cleanDisabled(true).validateMigrationNaming(true)
                .target(target)
                .load();
    }
}
