package com.grandis.nova.preorder.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * preorders 정리(V202610111500)의 실패 경로. 앞 버전까지 올린 DB 에 옛 상태 PAYABLE 행을 넣고 올리면,
 * 한 문장이라 키 · CHECK 가 모두 그대로 남은 채 멈추고, 그 행을 고친 뒤 repair → migrate 로 끝까지 오른다.
 */
class PreorderLeftoversMigrationTest {

    static final String BEFORE = "202610111304";
    static final String CLEANUP = "202610111500";

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
    @DisplayName("PAYABLE 행이 남아 있으면 키 · CHECK 를 그대로 둔 채 멈추고, 고친 뒤 repair → migrate 로 오른다")
    void leavesKeyAndChecksIntactWhenPayableRowRemains() {
        flyway(BEFORE).migrate();
        UUID id = payablePreorder();

        assertThatThrownBy(() -> flyway(CLEANUP).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("ck_preorder_status");

        assertThat(uniques()).as("유일 키도 지워지지 않는다").contains("uq_preorder_id_customer");
        assertThat(checkClause("ck_preorder_status")).as("옛 CHECK 그대로").contains("PAYABLE");
        assertThat(checkClause("ck_preorder_payable_from")).contains("PAYABLE");

        jdbc.update("UPDATE preorders SET status = 'REGISTERED' WHERE id = UUID_TO_BIN(?)", id.toString());
        flyway(CLEANUP).repair();
        flyway(CLEANUP).migrate();

        assertThat(uniques()).doesNotContain("uq_preorder_id_customer");
        assertThat(checkClause("ck_preorder_status")).doesNotContain("PAYABLE").contains("REGISTERED");
        assertThat(checkClause("ck_preorder_payable_from")).doesNotContain("PAYABLE").contains("REGISTERED");
    }

    /** 부모 행(회원 · 옵션 · 차수)은 이 시험의 관심이 아니라 외래 키 검사를 끄고 넣는다. 같은 연결이라 세션 설정이 이어진다. */
    private UUID payablePreorder() {
        UUID id = UUID.randomUUID();
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.update("""
                INSERT INTO preorders (id, preorder_token, customer_id, product_id, option_id, shipment_batch_id, queue_position,
                                       idempotency_key, product_title_snapshot, option_title_snapshot, unit_price_snapshot, status,
                                       payable_from, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), ?, UUID_TO_BIN(UUID()), UUID_TO_BIN(UUID()), UUID_TO_BIN(UUID()), UUID_TO_BIN(UUID()), 1,
                        'idem-1', 'Nova 1', '블랙', 1000, 'PAYABLE', '2026-10-11 00:00:00', '2026-10-11 00:00:00', '2026-10-11 00:00:00')
                """, id.toString(), UUID.randomUUID().toString());
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        return id;
    }

    private List<String> uniques() {
        return jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                 WHERE table_schema = 'shop' AND table_name = 'preorders' AND non_unique = 0
                """, String.class);
    }

    private String checkClause(String name) {
        return jdbc.queryForObject("""
                SELECT check_clause FROM information_schema.check_constraints
                 WHERE constraint_schema = 'shop' AND constraint_name = ?
                """, String.class, name);
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
