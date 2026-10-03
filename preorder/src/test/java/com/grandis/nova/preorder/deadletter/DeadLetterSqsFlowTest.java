package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.deadletter.application.DeadLetterAdminService;
import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.SqsIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * 실제 SQS(Floci)로 처리 실패 → DLQ → DB → 되돌리기 → 결과 기록까지 잇는다.
 * 처리 실패는 분배기가 이 테스트의 본문만 실패시켜 만든다(코드 · 스키마 문제를 흉내 — 고쳐 배포하면 처리된다).
 */
@SqsIntegrationTest
class DeadLetterSqsFlowTest {

    static final Duration TIMEOUT = Duration.ofSeconds(40);

    @Autowired
    TestQueues queues;

    @Autowired
    DeadLetterAdminService adminService;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    PreorderEventDispatcher dispatcher;

    @MockitoBean
    CatalogClient catalogClient;

    /** 여기 든 본문은 처리에 실패한다. 빼면 고친 것으로 본다. */
    final Set<String> broken = ConcurrentHashMap.newKeySet();

    ShopFixtures fixtures;
    Long preorderId;
    String body;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        doAnswer(invocation -> {
            if (broken.contains(invocation.<String>getArgument(0))) {
                throw new IllegalStateException("처리기 오류(테스트)");
            }
            return invocation.callRealMethod();
        }).when(dispatcher).dispatch(anyString());
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer());
        preorderId = accepted.preorder().id();
        long syncJobId = fixtures.workerSucceeds(preorderId, "REGISTER");
        body = """
                {"eventId":"%s","eventType":"EXTERNAL_JOB_SUCCEEDED","aggregateType":"PREORDER_SYNC_JOB",
                 "aggregateId":%d,"occurredAt":"2026-09-03T01:00:03.470Z",
                 "payload":{"syncJobId":%d,"preorderId":"%s","jobType":"REGISTER","externalNumber":"R-%s"}}
                """.formatted(ShopFixtures.unique(), syncJobId, syncJobId, AcceptFixtures.tokenOf(accepted),
                ShopFixtures.unique());
    }

    @Test
    void 고친_뒤_되돌리면_처리되고_DLQ_행은_SUCCEEDED_다() {
        broken.add(body);
        queues.send("preorder-events", body);
        Long id = awaitOpenRow();
        assertThat(jdbcTemplate.queryForObject("SELECT preorder_id FROM dead_letter_events WHERE id = ?", Long.class,
                id)).isEqualTo(preorderId);

        broken.remove(body);
        adminService.redrive(id, "admin");

        await().atMost(TIMEOUT).until(() -> "SUCCEEDED".equals(status(id)));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM preorders WHERE id = ?", String.class, preorderId))
                .isEqualTo("PAYABLE");
    }

    @Test
    void 고치지_않고_되돌리면_또_DLQ_로_와_새_행이_앞선_행을_가리킨다() {
        broken.add(body);
        queues.send("preorder-events", body);
        Long id = awaitOpenRow();

        adminService.redrive(id, "admin");

        await().atMost(TIMEOUT).until(() -> "REDRIVE_FAILED".equals(status(id)));
        await().atMost(TIMEOUT).until(() -> fixtures.count(
                "SELECT COUNT(*) FROM dead_letter_events WHERE redriven_from_id = ? AND status = 'OPEN'", id) == 1);
    }

    private Long awaitOpenRow() {
        await().alias("처리 실패 → DLQ → DB").atMost(TIMEOUT).until(() -> fixtures.count(
                "SELECT COUNT(*) FROM dead_letter_events WHERE body = ? AND status = 'OPEN'", body) == 1);
        return jdbcTemplate.queryForObject("SELECT id FROM dead_letter_events WHERE body = ? AND status = 'OPEN'",
                Long.class, body);
    }

    private String status(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM dead_letter_events WHERE id = ?", String.class, id);
    }
}
