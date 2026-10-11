package com.grandis.nova.payment.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 결과 불명 사유 칸(V202610111302). 앞 버전까지 올린 빈 DB 에 구버전 모양의 거래(문구 칸에만 사유 이름)를 넣고 올린 뒤,
 * 문구가 사유 이름과 대소문자 · 끝 공백까지 같은 거래만 채웠는지 본다. 같이 도는 구버전이 오류를 지워도(사유 칸은 모른다) 막히지 않는지도 본다.
 */
class UnknownReasonMigrationTest {

    static final String BEFORE = "202610111301";
    static final String ADD = "202610111302";
    static final List<String> REASONS = List.of("TIMEOUT", "CONNECTION_FAILURE", "UNREADABLE_RESPONSE", "LISTED_CODE",
            "UNLISTED_CODE", "UNEXPECTED_STATUS", "MISMATCHED_RESPONSE");

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
    @DisplayName("문구가 사유 이름과 대소문자 · 끝 공백까지 같은 거래만 채우고, 구버전이 코드 · 문구를 지워도 막히지 않는다")
    void fillsFromExactReasonNamesAndOldVersionCanStillClearErrors() {
        migrate(BEFORE);
        Map<String, UUID> reasons = new LinkedHashMap<>();
        REASONS.forEach(reason -> reasons.put(reason, transaction(reason)));
        UUID lowerCase = transaction("timeout");
        UUID trailingSpace = transaction("TIMEOUT ");
        UUID lookup = transaction("LOOKUP_TIMEOUT");
        UUID providerText = transaction("한도초과");
        UUID noMessage = transaction(null);

        migrate(ADD);

        reasons.forEach((reason, id) -> assertThat(unknownReason(id)).as(reason).isEqualTo(reason));
        assertThat(unknownReason(lowerCase)).isNull();
        assertThat(unknownReason(trailingSpace)).isNull();
        assertThat(unknownReason(lookup)).isNull();
        assertThat(unknownReason(providerText)).isNull();
        assertThat(unknownReason(noMessage)).isNull();

        // 구버전의 성공 확정 · 키 교체는 코드 · 문구만 지운다
        UUID timeout = reasons.get("TIMEOUT");
        assertThatCode(() -> jdbc.update("""
                UPDATE payment_transactions SET last_error_code = NULL, last_error_message = NULL WHERE id = UUID_TO_BIN(?)""",
                timeout.toString())).doesNotThrowAnyException();
    }

    /** 결과를 기다리는 승인 거래. 대상마다 진행 중 거래는 하나라 대상을 따로 둔다. */
    private UUID transaction(String message) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_transactions (id, target_type, target_id, transaction_type, provider_order_id, provider_payment_key,
                                                  amount, idempotency_key, status, attempt_count, last_error_code, last_error_message,
                                                  created_at)
                VALUES (UUID_TO_BIN(?), 'ORDER', UUID_TO_BIN(UUID()), 'CAPTURE', ?, 'payment-key', 1000, ?, 'PROCESSING', 1, 'CODE', ?,
                        UTC_TIMESTAMP(6))""", id.toString(), "p-" + id, "k-" + id, message);
        return id;
    }

    private String unknownReason(UUID id) {
        return jdbc.queryForObject("SELECT last_unknown_reason FROM payment_transactions WHERE id = UUID_TO_BIN(?)", String.class,
                id.toString());
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
