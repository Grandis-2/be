package com.grandis.nova.preorder.reconciliation;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.support.FakeExternalMock;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 일괄 키로 Mock 취소의 마감 시간. 마감을 0 으로 둬 시작하자마자 지난 것으로 만든다 — 남은 건은 Mock 을 부르지 않고
 * 조치 칸도 그대로 둔 채 RECONCILIATION_BATCH_DEADLINE 으로 돌려준다. 설정이 달라 컨텍스트를 따로 쓴다.
 */
@PreorderIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.reconciliation.external-cancel-batch-deadline=0s")
class ReconciliationExternalCancelDeadlineTest {

    static final FakeExternalMock MOCK = FakeExternalMock.start();

    @DynamicPropertySource
    static void externalMock(DynamicPropertyRegistry registry) {
        registry.add("external.mock.base-url", MOCK::url);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 마감_시간이_지나면_남은_건은_시작하지_않고_건별_결과로_돌려준다() throws Exception {
        String key = UUID.randomUUID().toString();
        MOCK.active(key);
        UUID findingId = finding(completedRun(), "CANCELED_BUT_MOCK_ACTIVE", key);

        mockMvc.perform(post("/api/v1/admin/preorders/reconciliation/findings/external-cancel").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"findingIds\":[\"%s\"]}".formatted(findingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].findingId").value(findingId.toString()))
                .andExpect(jsonPath("$.data.items[0].finding").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].errorCode").value("RECONCILIATION_BATCH_DEADLINE"));

        assertThat(MOCK.lookups()).isZero();
        assertThat(MOCK.cancels()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT action_status FROM reconciliation_findings WHERE id = ?",
                String.class, (Object) UuidBinary.toBytes(findingId))).isEqualTo("NOT_STARTED");
    }

    private UUID completedRun() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO reconciliation_runs (id, requested_by, product_id, status, started_at, cutoff_at,
                                                         finished_at, error_message, held_count, pending_sync_count,
                                                         canceling_count, dead_letter_count, created_at, updated_at)
                        VALUES (?, NULL, NULL, 'COMPLETED', ?, ?, ?, NULL, 0, 0, 0, 0, ?, ?)
                        """,
                UuidBinary.toBytes(id), now, now.minusMinutes(45), now, now, now);
        return id;
    }

    private UUID finding(UUID runId, String checkCode, String targetKey) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO reconciliation_findings (id, run_id, check_code, target_key, be_snapshot,
                                                             first_detected_at, action_status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, JSON_OBJECT('token', ?, 'status', 'CANCELED'), UTC_TIMESTAMP(6),
                                'NOT_STARTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """,
                UuidBinary.toBytes(id), UuidBinary.toBytes(runId), checkCode, targetKey, targetKey);
        return id;
    }
}
