package com.grandis.nova.batch.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.reconciliation.check.InternalCheck;
import com.grandis.nova.batch.reconciliation.check.Reconciler;
import com.grandis.nova.batch.support.BatchIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** 검사 트랜잭션의 제한 시간 — 걸린 조회를 끊고 판정 불가로 끝낸다. 시험용 be 내부 검사가 제한 시간보다 오래 걸리는 조회를 한다 */
@Import(ReconciliationTimeoutTest.SlowCheck.class)
@TestPropertySource(properties = {"nova.batch.reconciliation.run-timeout=1s", "external.mock.max-duration=1s"})
class ReconciliationTimeoutTest extends BatchIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class SlowCheck {

        @Bean
        @Primary
        Reconciler slowInternalCheckOnly(JdbcClient jdbc, JsonMapper json) {
            InternalCheck slow = (scope, findings) -> jdbc.sql("SELECT SLEEP(5)").query(Integer.class).single();
            return new Reconciler(List.of(), List.of(slow), json);
        }
    }

    @Autowired
    ReconciliationService service;

    @Test
    void 제한_시간을_넘긴_조회는_끊고_판정_불가로_끝낸다() {
        long started = System.nanoTime();

        service.runScheduled();

        // 5초 조회를 기다리지 않고 제한 시간(1초) 무렵에 끊는다
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(onlyRun()).containsEntry("status", "FAILED")
                .containsEntry("error_message", "검사 제한 시간(1초)을 넘음");
        // 다음 실행을 막지 않는다
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reconciliation_runs WHERE active_marker IS NOT NULL")
                .query(Long.class).single()).isZero();
    }

    private Map<String, Object> onlyRun() {
        return jdbc.sql("SELECT status, error_message FROM reconciliation_runs").query().singleRow();
    }
}
