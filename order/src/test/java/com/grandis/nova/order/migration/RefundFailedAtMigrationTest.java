package com.grandis.nova.order.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 환불 실패 시각 채우기(V202610111303). 앞 버전까지 올린 빈 DB 에 구버전이 남긴 모양의 주문 · 이력을 넣고 올린 뒤,
 * "취소 중이고 마지막 이력이 시스템의 환불 실패" 인 주문만 그 이력 시각으로 채웠는지 본다.
 */
class RefundFailedAtMigrationTest {

    static final String BEFORE = "202610111302";
    static final String ADD = "202610111303";
    static final String FAILED_AT = "2026-10-11 03:00:00.123456";

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
    @DisplayName("취소 중이고 마지막 이력이 시스템의 환불 실패인 주문만 그 이력 시각으로 채운다")
    void fillsOnlyCancelingOrdersWhoseLastEventIsSystemRefundFailure() {
        migrate(BEFORE);
        UUID failed = order("CANCELING", "SYSTEM", "REFUND_FAILED");
        UUID failedByAdmin = order("CANCELING", "ADMIN", "REFUND_FAILED");
        UUID otherReason = order("CANCELING", "SYSTEM", "PREORDER_CANCEL:USER");
        UUID canceled = order("CANCELED", "SYSTEM", "REFUND_FAILED");
        UUID failedThenNoted = order("CANCELING", "SYSTEM", "REFUND_FAILED");
        event(failedThenNoted, 3, "ADMIN", "고객 연락함", "2026-10-11 04:00:00");
        jdbc.update("UPDATE orders SET event_sequence = 3 WHERE id = UUID_TO_BIN(?)", failedThenNoted.toString());

        migrate(ADD);

        assertThat(refundFailedAt(failed)).as("이력 시각 그대로(마이크로초까지)").isEqualTo(LocalDateTime.parse("2026-10-11T03:00:00.123456"));
        assertThat(refundFailedAt(failedByAdmin)).isNull();
        assertThat(refundFailedAt(otherReason)).isNull();
        assertThat(refundFailedAt(canceled)).as("환불이 끝나 취소된 주문은 채우지 않는다").isNull();
        assertThat(refundFailedAt(failedThenNoted)).as("실패 뒤에 다른 이력이 붙었다").isNull();
    }

    /** 이력 두 줄(만든 때 · 마지막)을 가진 장바구니 주문. 주문의 이력 번호는 마지막 이력의 번호다. */
    private UUID order(String status, String lastActor, String lastReason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, order_token, customer_id, source, status, total_amount, payment_due_at, ship_to_name, ship_to_phone,
                                    ship_to_postal_code, ship_to_line1, event_sequence, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), ?, UUID_TO_BIN(UUID()), 'CART', ?, 1000, '2026-10-11 00:10:00', '홍길동', '010-0000-0000',
                        '04524', '서울시', 2, '2026-10-11 00:00:00', '2026-10-11 00:00:00')""", id.toString(), id.toString(), status);
        event(id, 1, "USER", null, "2026-10-11 00:00:00");
        event(id, 2, lastActor, lastReason, FAILED_AT);
        return id;
    }

    private void event(UUID orderId, long sequence, String actor, String reason, String createdAt) {
        jdbc.update("""
                INSERT INTO order_events (order_id, event_sequence, from_status, to_status, actor, reason, created_at)
                VALUES (UUID_TO_BIN(?), ?, NULL, 'CANCELING', ?, ?, ?)""", orderId.toString(), sequence, actor, reason, createdAt);
    }

    private LocalDateTime refundFailedAt(UUID orderId) {
        return jdbc.queryForObject("SELECT refund_failed_at FROM orders WHERE id = UUID_TO_BIN(?)", LocalDateTime.class,
                orderId.toString());
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "connectionCollation=utf8mb4_unicode_ci",
                        "root", mysql.getPassword())
                .locations("filesystem:" + System.getProperty("nova.migrations-path"))
                .schemas("shop").defaultSchema("shop")
                .createSchemas(false).cleanDisabled(true).validateMigrationNaming(true)
                .target(target)
                .load().migrate();
    }
}
