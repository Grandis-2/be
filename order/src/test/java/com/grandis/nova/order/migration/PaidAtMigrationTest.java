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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 시각 칸 · 채우기(V202610161600 · V202610161601). 앞 버전까지 올린 빈 DB 에 구버전이 남긴 모양의 주문 · 이력을 넣고 올린 뒤,
 * 승인을 반영한 주문만(그 뒤 출고 · 환불됐어도) 승인 반영 이력의 시각으로 채웠는지 본다.
 */
class PaidAtMigrationTest {

    static final String BEFORE = "202610160913";
    static final String FILL = "202610161601";
    static final String APPROVED_AT = "2026-10-16 03:00:00.123456";

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
    @DisplayName("승인을 반영한 주문만 승인 반영 이력의 시각으로 채우고, 결제 전 상태에는 결제 시각을 넣을 수 없다")
    void fillsOnlyApprovedOrdersFromTheApprovalEvent() {
        migrate(BEFORE);
        UUID paid = order("AWAITING_CONFIRMATION");
        approval(paid, 3);
        UUID shipped = order("SHIPPED");
        approval(shipped, 3);
        event(shipped, 4, "AWAITING_CONFIRMATION", "SHIPPED", "2026-10-16 05:00:00");
        UUID refunded = order("CANCELED");
        approval(refunded, 3);
        event(refunded, 4, "AWAITING_CONFIRMATION", "CANCELING", "2026-10-16 05:00:00");
        event(refunded, 5, "CANCELING", "CANCELED", "2026-10-16 05:10:00");
        UUID declined = order("AWAITING_PAYMENT");
        event(declined, 3, "AUTHORIZING", "AWAITING_PAYMENT", APPROVED_AT);
        UUID unpaidCanceled = order("CANCELED");
        event(unpaidCanceled, 3, "AWAITING_PAYMENT", "CANCELED", APPROVED_AT);

        migrate(FILL);

        LocalDateTime approvedAt = LocalDateTime.parse("2026-10-16T03:00:00.123456");
        assertThat(paidAt(paid)).as("이력 시각 그대로(마이크로초까지)").isEqualTo(approvedAt);
        assertThat(paidAt(shipped)).as("출고된 주문도 결제 시각은 승인 반영 때다").isEqualTo(approvedAt);
        assertThat(paidAt(refunded)).as("환불로 취소돼도 기록으로 남는다").isEqualTo(approvedAt);
        assertThat(paidAt(declined)).as("거절돼 결제 대기로 돌아갔다").isNull();
        assertThat(paidAt(unpaidCanceled)).as("미결제 취소").isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET paid_at = NOW(6) WHERE id = UUID_TO_BIN(?)",
                declined.toString()))
                .as("결제 전 상태에는 결제 시각이 없다")
                .hasMessageContaining("ck_order_paid");
    }

    /** 만든 때 · 승인 요청 이력을 가진 장바구니 주문. 이력 번호는 마지막 이력에 맞춰 넣는 쪽이 고친다. */
    private UUID order(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, order_token, customer_id, source, status, total_amount, payment_due_at, ship_to_name, ship_to_phone,
                                    ship_to_postal_code, ship_to_line1, event_sequence, created_at, updated_at)
                VALUES (UUID_TO_BIN(?), ?, UUID_TO_BIN(UUID()), 'CART', ?, 1000, '2026-10-16 00:10:00', '홍길동', '010-0000-0000',
                        '04524', '서울시', 2, '2026-10-16 00:00:00', '2026-10-16 00:00:00')""", id.toString(), id.toString(), status);
        event(id, 1, null, "AWAITING_PAYMENT", "2026-10-16 00:00:00");
        event(id, 2, "AWAITING_PAYMENT", "AUTHORIZING", "2026-10-16 02:59:00");
        return id;
    }

    private void approval(UUID orderId, long sequence) {
        event(orderId, sequence, "AUTHORIZING", "AWAITING_CONFIRMATION", APPROVED_AT);
    }

    private void event(UUID orderId, long sequence, String from, String to, String createdAt) {
        jdbc.update("""
                INSERT INTO order_events (order_id, event_sequence, from_status, to_status, actor, reason, created_at)
                VALUES (UUID_TO_BIN(?), ?, ?, ?, 'SYSTEM', NULL, ?)""", orderId.toString(), sequence, from, to, createdAt);
        jdbc.update("UPDATE orders SET event_sequence = ? WHERE id = UUID_TO_BIN(?)", sequence, orderId.toString());
    }

    private LocalDateTime paidAt(UUID orderId) {
        return jdbc.queryForObject("SELECT paid_at FROM orders WHERE id = UUID_TO_BIN(?)", LocalDateTime.class,
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
