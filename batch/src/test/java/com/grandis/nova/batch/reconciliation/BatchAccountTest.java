package com.grandis.nova.batch.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.grandis.nova.batch.support.FakeMockLedger;
import com.grandis.nova.batch.support.MySqlTestContainer;
import com.grandis.nova.batch.support.TestData;
import com.grandis.nova.batch.support.TestData.BePreorderRow;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 운영 batch 계정과 같은 권한(업무 표 SELECT, 결과 표만 쓰기)으로 비교 규칙까지 검사를 끝까지 돌린다.
 * 검사 코드가 업무 표에 쓰기를 하면 DB 가 막아 이 시험이 깨진다. 시험 데이터는 root 로 넣는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class BatchAccountTest {

    private static final String USER = "batch_test";
    private static final String PASSWORD = "batch-test-only";
    private static final FakeMockLedger MOCK = FakeMockLedger.start();
    private static final Instant TWO_HOURS_AGO = Instant.now().minus(Duration.ofHours(2));

    @Autowired
    ReconciliationService service;

    /** 컨텍스트가 쓰는 연결 — batch 계정 */
    @Autowired
    JdbcClient batchAccount;

    private final JdbcClient root = JdbcClient.create(rootDataSource());

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        MySQLContainer mysql = MySqlTestContainer.get();
        createBatchAccount(mysql);
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        // 마이그레이션은 batch 계정으로 못 돌린다(DDL 권한 없음)
        registry.add("spring.flyway.user", () -> "root");
        registry.add("spring.flyway.password", mysql::getPassword);
        registry.add("external.mock.base-url", MOCK::url);
    }

    @BeforeEach
    void clear() {
        new TestData(root).clear();
        MOCK.reset();
    }

    @Test
    void 결과_표에만_쓰는_권한으로_검사를_끝까지_돌린다() {
        TestData data = new TestData(root);
        BePreorderRow registered = data.preorder("폰", "t-1", "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
        data.registerJobReady(registered, 1, TWO_HOURS_AGO, null);
        data.attempt(registered, "SUCCESS", TWO_HOURS_AGO);
        data.preorder("폰", "t-2", "PENDING_SYNC", "PENDING", TWO_HOURS_AGO);
        MOCK.active("g-1", "R-GHOST", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "SKU", TWO_HOURS_AGO);

        service.runScheduled();

        assertThat(root.sql("SELECT status FROM reconciliation_runs").query(String.class).single()).isEqualTo("COMPLETED");
        // 키마다 보는 규칙과 이벤트 · 시도 · 순번을 읽는 SQL 규칙이 모두 돌았다
        assertThat(root.sql("SELECT check_code, target_key FROM reconciliation_findings").query().listOfRows())
                .extracting(f -> f.get("check_code"), f -> f.get("target_key"))
                .containsExactlyInAnyOrder(
                        tuple("MOCK_ONLY", "g-1"),
                        tuple("REGISTERED_NOT_IN_MOCK", "t-1"),
                        tuple("STUCK", "t-1"),
                        tuple("STUCK", "t-2"),
                        tuple("OUTBOX_MISSING", "t-2"));
    }

    /** Spring 은 권한 오류를 BadSqlGrammarException 으로 감싸고 MySQL 의 문구는 원인 예외에 남긴다 */
    @Test
    void batch_계정은_업무_표와_건별_결과를_바꿀_수_없다() {
        assertThatThrownBy(() -> batchAccount.sql("UPDATE preorders SET status = status").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("UPDATE command denied");
        assertThatThrownBy(() -> batchAccount.sql("UPDATE reconciliation_findings SET action_note = NULL").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("UPDATE command denied");
        assertThatThrownBy(() -> batchAccount.sql("DELETE FROM reconciliation_runs").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("DELETE command denied");
    }

    /** 결과 표가 있어야 표 단위 권한을 줄 수 있어 마이그레이션부터 올린다(컨텍스트가 뜨기 전이다) */
    private static void createBatchAccount(MySQLContainer mysql) {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), "root", mysql.getPassword())
                .locations("filesystem:" + System.getProperty("nova.migrations-path"))
                .defaultSchema("shop").schemas("shop").createSchemas(false)
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER IF NOT EXISTS '" + USER + "'@'%' IDENTIFIED BY '" + PASSWORD + "'");
            statement.execute("GRANT SELECT ON shop.* TO '" + USER + "'@'%'");
            statement.execute("GRANT INSERT, UPDATE ON shop.reconciliation_runs TO '" + USER + "'@'%'");
            statement.execute("GRANT INSERT ON shop.reconciliation_findings TO '" + USER + "'@'%'");
        } catch (SQLException e) {
            throw new IllegalStateException("batch 시험 계정을 만들지 못했다", e);
        }
    }

    private static DriverManagerDataSource rootDataSource() {
        MySQLContainer mysql = MySqlTestContainer.get();
        return new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
    }
}
