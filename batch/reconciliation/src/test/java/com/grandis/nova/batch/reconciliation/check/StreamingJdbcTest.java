package com.grandis.nova.batch.reconciliation.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grandis.nova.batch.reconciliation.support.ReconciliationIntegrationTest;
import com.grandis.nova.batch.reconciliation.support.TestData.BePreorderRow;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** be 내부 확인할 것의 조회가 검사 트랜잭션의 연결로 한 행씩 오고, 상한에 걸려 도중에 끊겨도 연결이 다시 쓸 수 있는 상태인지 본다 */
class StreamingJdbcTest extends ReconciliationIntegrationTest {

    private static final Instant TWO_HOURS_AGO = Instant.now().minus(Duration.ofHours(2));

    @Autowired
    StreamingJdbc streaming;

    @Autowired
    PaidButRegisteredCheck paidButRegistered;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JsonMapper json;

    private TransactionTemplate snapshot;

    @BeforeEach
    void snapshotTransaction() {
        snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    @Test
    void 한_행씩_받는_동안에는_같은_연결로_다른_조회를_못_한다() {
        // 결과를 다 받아 두는 방식이면 이 조회가 된다. 다른 연결이면(검사 트랜잭션 밖이면) 역시 된다
        assertThatThrownBy(() -> snapshot.executeWithoutResult(status -> streaming
                .sql("SELECT 1 UNION ALL SELECT 2")
                .query(rs -> {
                    jdbc.sql("SELECT 1").query(Integer.class).single();
                })))
                .hasStackTraceContaining("Streaming result set");
    }

    @Test
    void 상한에_걸려_도중에_끊겨도_같은_연결로_다음_조회를_한다() {
        for (String token : new String[]{"s-1", "s-2", "s-3"}) {
            BePreorderRow preorder = data.preorder("폰", token, "REGISTERED", "SUCCEEDED", TWO_HOURS_AGO);
            data.paidOrder(preorder, "SUCCEEDED", TWO_HOURS_AGO);
        }

        snapshot.executeWithoutResult(status -> {
            FindingSink findings = new FindingSink(json, 2);
            Instant now = Instant.now();
            assertThatThrownBy(() -> paidButRegistered.inspect(new RunScope(null, now, now, 2, key -> false), findings))
                    .isInstanceOf(TooManyFindings.class);
            assertThat(findings.size()).isEqualTo(2);
            // 끊긴 결과가 닫히지 않았으면 여기서 "Streaming result set … is still active" 로 실패한다
            assertThat(jdbc.sql("SELECT COUNT(*) FROM preorders").query(Long.class).single()).isEqualTo(3L);
        });
    }
}
