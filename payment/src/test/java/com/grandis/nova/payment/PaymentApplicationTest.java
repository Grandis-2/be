package com.grandis.nova.payment;

import com.grandis.nova.common.jpa.StorageClock;
import com.grandis.nova.payment.config.TransactionIsolationVerifier;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@PaymentIntegrationTest
class PaymentApplicationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationContext context;

    @Autowired
    Clock clock;

    @Test
    void contextLoadsWithReadCommittedIsolation() {
        assertThat(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("READ-COMMITTED");
    }

    // 빈이 빠지면 기동 때 격리 수준을 아무도 확인하지 않는다.
    @Test
    void isolationVerifierIsRegistered() {
        assertThat(context.getBeansOfType(TransactionIsolationVerifier.class)).hasSize(1);
    }

    /*
     * 운영 컨텍스트의 시계가 저장 해상도(마이크로초)로 내린 시계인가. 결제 시도 · 리스 시각이 DB 칼럼(datetime(6))과
     * 어긋나지 않게 하는 전제다. 시각을 보고 판정하면 macOS(마이크로초 시계)에서 늘 통과하므로 빈 자체를 비교한다.
     */
    @Test
    void applicationClockIsAtStorageResolution() {
        assertThat(clock).isEqualTo(StorageClock.atStorageResolution(Clock.systemUTC()));
    }

    @Test
    void migrationsAreApplied() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'shop'", String.class);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);

        assertThat(tables).contains("payments", "payment_transactions", "flyway_schema_history");
        assertThat(failed).isZero();
    }
}
