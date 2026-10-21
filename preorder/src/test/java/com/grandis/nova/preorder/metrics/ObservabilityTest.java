package com.grandis.nova.preorder.metrics;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.OpenAlertBacklog;
import com.grandis.nova.preorder.cancel.ExpiryCancelService;
import com.grandis.nova.preorder.deadletter.DeadLetterBacklog;
import com.grandis.nova.preorder.deadletter.DeadLetters;
import com.grandis.nova.preorder.deadletter.IncomingDeadLetter;
import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import com.grandis.nova.preorder.event.PreorderEventHandler;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.PaymentOverdue;
import com.grandis.nova.preorder.preorder.PreorderFact;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.AdmissionTickets;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.syncjob.RegisterJobReady;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import com.grandis.nova.preorder.syncjob.SyncActor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

/** 부하 시험 · 운영에서 볼 지표가 실제로 기록되고 /actuator/prometheus 로 나가는지. 레지스트리를 공유하므로 증가분으로 본다. */
@PreorderIntegrationTest
@AutoConfigureMetrics
class ObservabilityTest {

    @Autowired
    MeterRegistry registry;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderEventDispatcher dispatcher;

    @Autowired
    OutboxWriter outboxWriter;

    @Autowired
    StateGauges stateGauges;

    @Autowired
    Preorders preorders;

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    PreorderLedger ledger;

    @Autowired
    ExpiryCancelService expiryCancelService;

    @Autowired
    Campaigns campaigns;

    @Autowired
    DeadLetterBacklog deadLetterBacklog;

    @Autowired
    DeadLetters deadLetters;

    @Autowired
    OpenAlertBacklog openAlertBacklog;

    @Autowired
    SyncJobs syncJobs;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PrometheusMeterRegistry prometheus;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
    }

    @Test
    void 접수는_결과별_시간과_회차_잠금_대기를_남긴다() {
        long accepted = timerCount("preorder.accept", "outcome", "accepted");
        long invalidTicket = timerCount("preorder.accept", "outcome", "ADMISSION_TICKET_INVALID");
        long lockWaits = registry.get("preorder.campaign.lock.wait").timer().count();

        accepts.accept(fixtures.customer());
        PreorderProduct product = fixtures.openPreorderProduct();
        assertThatThrownBy(() -> acceptService.acceptByCustomer(fixtures.customer(), product.productId(),
                product.productId(), product.optionId(), "key-" + ShopFixtures.unique(), "et_forged"))
                .isInstanceOf(BusinessException.class);

        assertThat(timerCount("preorder.accept", "outcome", "accepted")).isEqualTo(accepted + 1);
        assertThat(timerCount("preorder.accept", "outcome", "ADMISSION_TICKET_INVALID")).isEqualTo(invalidTicket + 1);
        assertThat(registry.get("preorder.campaign.lock.wait").timer().count()).isEqualTo(lockWaits + 1);
    }

    /** 경보가 "값 없음"으로 빠지지 않게 접수 전부터 결과 · 경로 조합이 모두 있다. */
    @Test
    void 접수_업무_지표는_접수_전부터_모든_조합이_있다() {
        for (String channel : List.of("user", "admin")) {
            for (String result : List.of("success", "rejected", "error")) {
                assertThat(registry.find("nova.preorder.created").tag("result", result).tag("channel", channel)
                        .counter()).as("%s · %s", result, channel).isNotNull();
            }
        }
    }

    /** 업무 지표는 새 예약만 성공으로 센다. 재전송은 세지 않고, 업무 거절(4xx)과 장애(5xx · 예외)를 나눈다. */
    @Test
    void 접수_업무_지표는_새_예약만_성공으로_세고_거절과_장애를_나눠_센다() {
        double userSuccess = created("success", "user");
        double adminSuccess = created("success", "admin");
        double rejected = created("rejected", "user");
        double error = created("error", "user");
        PreorderProduct product = fixtures.openPreorderProduct();
        accepts.stubCatalog(product);
        UUID customerId = fixtures.customer();
        String key = "key-" + ShopFixtures.unique();
        String ticket = AdmissionTickets.issue(product.productId(), customerId, Instant.now());

        acceptService.acceptByCustomer(customerId, product.productId(), product.productId(), product.optionId(), key,
                ticket);
        acceptService.acceptByCustomer(customerId, product.productId(), product.productId(), product.optionId(), key,
                ticket);
        assertThatThrownBy(() -> acceptService.acceptByCustomer(fixtures.customer(), product.productId(),
                product.productId(), product.optionId(), "key-" + ShopFixtures.unique(), "et_forged"))
                .isInstanceOf(BusinessException.class);
        PreorderProduct catalogDown = fixtures.openPreorderProduct();
        given(catalogClient.getProduct(catalogDown.productId())).willThrow(new ResourceAccessException("timeout"));
        assertThatThrownBy(() -> accepts.submit(fixtures.customer(), catalogDown)).isInstanceOf(RuntimeException.class);
        acceptService.acceptByAdmin(fixtures.customer(), product.productId(), product.optionId(),
                "key-" + ShopFixtures.unique(), "전화 접수", null);

        assertThat(created("success", "user")).as("재전송은 새 예약이 아니다").isEqualTo(userSuccess + 1);
        assertThat(created("rejected", "user")).isEqualTo(rejected + 1);
        assertThat(created("error", "user")).as("catalog 장애(503)는 업무 거절이 아니다").isEqualTo(error + 1);
        assertThat(created("success", "admin")).isEqualTo(adminSuccess + 1);
    }

    @Test
    void 접수_중인_회차만_센다() {
        Instant now = Instant.now();
        long before = campaigns.countAccepting(now);
        UUID accepting = fixtures.openPreorderProduct().productId();
        UUID notOpen = fixtures.preorderProduct(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)))
                .productId();
        UUID closed = fixtures.preorderProduct(now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)))
                .productId();
        UUID hidden = fixtures.openPreorderProduct().productId();
        UUID paused = fixtures.openPreorderProduct().productId();
        for (UUID productId : List.of(accepting, notOpen, closed)) {
            fixtures.listing(productId, "상품", "ACTIVE", true);
        }
        fixtures.listing(hidden, "상품", "ACTIVE", false);
        fixtures.listing(paused, "상품", "PAUSED", true);

        long expected = before + 1;
        assertThat(campaigns.countAccepting(now)).as("오픈 전 · 마감 · 비공개 · 판매 중지는 빠진다").isEqualTo(expected);
        // 기대값과 같은 시각으로 세도록 시계를 고정한 인스턴스로 갱신한다
        SimpleMeterRegistry gauges = new SimpleMeterRegistry();
        new StateGauges(preorders, deadLetterBacklog, openAlertBacklog, campaigns, syncJobs, gauges,
                Clock.fixed(now, ZoneOffset.UTC)).refresh();
        assertThat(gauges.get(StateGauges.ACCEPTING_CAMPAIGNS_METRIC).gauge().value()).isEqualTo(expected);
    }

    @Test
    void 포기한_외부_작업을_종류별로_센다() {
        SimpleMeterRegistry gauges = new SimpleMeterRegistry();
        StateGauges counting = new StateGauges(preorders, deadLetterBacklog, openAlertBacklog, campaigns, syncJobs,
                gauges, Clock.systemUTC());
        counting.refresh();
        double before = deadLetterJobs(gauges, "REGISTER");

        fixtures.deadLetter(accepts.accept(fixtures.customer()).preorder().id());
        counting.refresh();

        assertThat(deadLetterJobs(gauges, "REGISTER")).isEqualTo(before + 1);
        assertThat(deadLetterJobs(gauges, "CANCEL")).as("기동 때부터 종류마다 있다").isNotNegative();
    }

    /** 어느 상품이 막혔는지 상품별로 낸다. 포기한 작업이 없는 상품은 시계열이 없다. */
    @Test
    void 포기한_외부_작업을_상품별로_나눠_센다() {
        SimpleMeterRegistry gauges = new SimpleMeterRegistry();
        StateGauges counting = new StateGauges(preorders, deadLetterBacklog, openAlertBacklog, campaigns, syncJobs,
                gauges, Clock.systemUTC());
        AcceptResult blocked = accepts.accept(fixtures.customer());
        AcceptResult fine = accepts.accept(fixtures.customer());
        fixtures.deadLetter(blocked.preorder().id());

        counting.refresh();

        assertThat(gauges.get(StateGauges.DEAD_LETTER_JOBS_BY_PRODUCT_METRIC).tag("jobType", "REGISTER")
                .tag("productId", blocked.preorder().productId().toString()).gauge().value()).isEqualTo(1);
        assertThat(gauges.find(StateGauges.DEAD_LETTER_JOBS_BY_PRODUCT_METRIC)
                .tag("productId", fine.preorder().productId().toString()).gauges()).isEmpty();
    }

    /** 무엇이 쌓였는지 종류 · 사유별로 낸다. 받지 않는 종류는 OTHER 하나로 모은다 — 시계열이 끝없이 늘지 않게. */
    @Test
    void 기다리는_DLQ_를_종류와_사유별로_나눠_센다() {
        SimpleMeterRegistry gauges = new SimpleMeterRegistry();
        StateGauges counting = new StateGauges(preorders, deadLetterBacklog, openAlertBacklog, campaigns, syncJobs,
                gauges, Clock.systemUTC());
        counting.refresh();
        double processingBefore = waitingDeadLetters(gauges, "PREORDER_PAYMENT_STARTED", "PROCESSING_FAILED");
        double otherBefore = waitingDeadLetters(gauges, "OTHER", "UNKNOWN_EVENT_TYPE");
        double totalBefore = gauges.get("preorder.dlq.waiting").gauge().value();

        recordDeadLetter("PREORDER_PAYMENT_STARTED");
        recordDeadLetter("SOMETHING_NEW_" + ShopFixtures.unique());
        recordDeadLetter("SOMETHING_ELSE_" + ShopFixtures.unique());
        counting.refresh();

        assertThat(waitingDeadLetters(gauges, "PREORDER_PAYMENT_STARTED", "PROCESSING_FAILED"))
                .isEqualTo(processingBefore + 1);
        assertThat(waitingDeadLetters(gauges, "OTHER", "UNKNOWN_EVENT_TYPE")).isEqualTo(otherBefore + 2);
        assertThat(gauges.get("preorder.dlq.waiting").gauge().value()).as("합계는 그대로").isEqualTo(totalBefore + 3);
    }

    @Test
    void 이벤트는_종류별_처리_결과와_소비_지연을_남긴다() {
        AcceptResult result = accepts.accept(fixtures.customer());
        UUID jobId = fixtures.workerSucceeds(result.preorder().id(), "REGISTER");
        long succeeded = timerCount("preorder.events.handle", "eventType", "EXTERNAL_JOB_SUCCEEDED");
        long unknown = timerCount("preorder.events.handle", "eventType", "UNKNOWN");

        dispatcher.dispatch("""
                {"eventId":"%s","eventType":"EXTERNAL_JOB_SUCCEEDED","aggregateType":"PREORDER_SYNC_JOB",
                 "aggregateId":"%s","occurredAt":"2026-09-03T01:00:03.470Z",
                 "payload":{"syncJobId":"%s","preorderId":"%s","jobType":"REGISTER","externalNumber":"R-%s"}}
                """.formatted(ShopFixtures.unique(), jobId, jobId, AcceptFixtures.tokenOf(result),
                ShopFixtures.unique()));
        assertThatThrownBy(() -> dispatcher.dispatch("not-json")).isInstanceOf(RuntimeException.class);

        assertThat(timerCount("preorder.events.handle", "eventType", "EXTERNAL_JOB_SUCCEEDED"))
                .isEqualTo(succeeded + 1);
        assertThat(timerCount("preorder.events.handle", "eventType", "UNKNOWN")).isEqualTo(unknown + 1);
        assertThat(registry.get("preorder.events.lag").tag("eventType", "EXTERNAL_JOB_SUCCEEDED").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void 발생_시각이_미래면_소비_지연을_0_으로_남긴다() {
        AcceptResult result = accepts.accept(fixtures.customer());
        UUID jobId = fixtures.workerSucceeds(result.preorder().id(), "REGISTER");
        Timer lag = registry.timer("preorder.events.lag", "eventType", "EXTERNAL_JOB_SUCCEEDED");
        long count = lag.count();
        double total = lag.totalTime(TimeUnit.NANOSECONDS);

        dispatcher.dispatch("""
                {"eventId":"%s","eventType":"EXTERNAL_JOB_SUCCEEDED","aggregateType":"PREORDER_SYNC_JOB",
                 "aggregateId":"%s","occurredAt":"2099-01-01T00:00:00Z",
                 "payload":{"syncJobId":"%s","preorderId":"%s","jobType":"REGISTER","externalNumber":"R-%s"}}
                """.formatted(ShopFixtures.unique(), jobId, jobId, AcceptFixtures.tokenOf(result),
                ShopFixtures.unique()));

        assertThat(lag.count()).isEqualTo(count + 1);
        assertThat(lag.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(total);
    }

    @Test
    void 아웃박스_발행_결과를_종류별로_센다() {
        double published = publishCount();

        transactionTemplate.executeWithoutResult(status -> outboxWriter.append(
                new RegisterJobReady(UUID.randomUUID(), ShopFixtures.unique(), UUID.randomUUID(), "{}", SyncActor.SYSTEM, 1)));

        await().atMost(Duration.ofSeconds(5)).until(() -> publishCount() == published + 1);
    }

    @Test
    void 상태_지표는_갱신할_때_DB_에서_센다() {
        accepts.accept(fixtures.customer());
        jdbcTemplate.update("""
                INSERT INTO preorder_dead_letter_events (id, source_queue, message_id, body, failure_reason, receive_count,
                                                status, created_at, updated_at)
                VALUES (?, 'preorder-events', ?, 'not-json', 'UNREADABLE_BODY', 5, 'OPEN',
                        UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, UTC_TIMESTAMP(6))
                """, UuidBinary.toBytes(UUID.randomUUID()), ShopFixtures.unique());
        UUID openAlertProduct = fixtures.preorderProduct(Instant.now().minus(Duration.ofHours(2)),
                Instant.now().plus(Duration.ofHours(1))).productId();
        jdbcTemplate.update("""
                INSERT INTO preorder_open_alert_dispatches (product_id, last_customer_id, created_at, completed_at)
                VALUES (?, NULL, UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, NULL)
                """, (Object) UuidBinary.toBytes(openAlertProduct));

        stateGauges.refresh();

        assertThat(gauge("preorder.status.count", "status", "PENDING_SYNC")).isPositive();
        assertThat(registry.get("preorder.dlq.waiting").gauge().value()).isPositive();
        assertThat(registry.get("preorder.dlq.waiting.oldest.age").gauge().value()).as("초")
                .isGreaterThanOrEqualTo(3600);
        assertThat(registry.get("preorder.open_alert.pending").gauge().value()).isPositive();
        assertThat(registry.get("preorder.open_alert.pending.oldest.age").gauge().value()).as("초")
                .isGreaterThanOrEqualTo(3600);
        // 다른 시험의 주기 작업이 이 발송을 집지 않게 닫는다
        jdbcTemplate.update("UPDATE preorder_open_alert_dispatches SET completed_at = UTC_TIMESTAMP(6) WHERE product_id = ?",
                (Object) UuidBinary.toBytes(openAlertProduct));
    }

    /** 공유 DB 라 전체 수가 아니라 이 시험이 더한 만큼 늘었는지 본다. */
    @Test
    void 결제_기한이_지난_예약_수를_만료_대기와_막힌_수로_나눠_센다() {
        PaymentOverdue before = preorders.countPaymentOverdue(Instant.now());
        overdue();
        String rejected = overdue();
        expiryCancelService.expire(rejected);
        UUID rejectedId = preorders.getByToken(rejected).id();
        transactionTemplate.executeWithoutResult(status -> ledger.fire(rejectedId,
                new PreorderFact.CancelRejected("REJECTED_BY_ORDER:PAID", null)));

        stateGauges.refresh();

        assertThat(registry.get("preorder.expiry.overdue").gauge().value()).isGreaterThanOrEqualTo(before.overdue() + 1);
        assertThat(registry.get("preorder.expiry.blocked").gauge().value()).isGreaterThanOrEqualTo(before.blocked() + 1);
    }

    /** 외부 등록을 확인해 결제 가능으로 만들고 결제 기한을 넘긴 예약. @return 예약 공개 UUID */
    private String overdue() {
        AcceptResult accepted = accepts.accept(fixtures.customer());
        UUID preorderId = accepted.preorder().id();
        String token = AcceptFixtures.tokenOf(accepted);
        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(
                fixtures.workerSucceeds(preorderId, "REGISTER"), token, "REGISTER", "R-" + ShopFixtures.unique()));
        fixtures.passPaymentDue(preorderId);
        return token;
    }

    @Test
    void catalog_캐시_적중률과_지표를_prometheus_로_내보낸다() throws Exception {
        accepts.accept(fixtures.customer());

        assertThat(registry.get("cache.gets").tag("cache", "catalog.products").functionCounters()).isNotEmpty();
        assertThat(prometheus.scrape()).contains(
                "preorder_accept_seconds",
                "preorder_campaign_lock_wait_seconds_bucket",
                "preorder_status_count",
                "# TYPE preorder_outbox_unpublished gauge",
                "# TYPE preorder_outbox_unpublished_max_attempts gauge",
                "hikaricp_connections_pending",
                "resilience4j_circuitbreaker_state",
                "resilience4j_retry_calls",
                "resilience4j_bulkhead_available_concurrent_calls");
    }

    private double created(String result, String channel) {
        Counter counter = registry.find("nova.preorder.created").tag("result", result).tag("channel", channel)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private long timerCount(String name, String tag, String value) {
        Timer timer = registry.find(name).tag(tag, value).timer();
        return timer == null ? 0 : timer.count();
    }

    private double publishCount() {
        return registry.find("preorder.outbox.publish").tag("eventType", "REGISTER_JOB_READY")
                .tag("outcome", "success").counters().stream().mapToDouble(counter -> counter.count()).sum();
    }

    private double gauge(String name, String tag, String value) {
        Gauge gauge = registry.get(name).tag(tag, value).gauge();
        return gauge.value();
    }

    private void recordDeadLetter(String eventType) {
        deadLetters.record(new IncomingDeadLetter("preorder-events", ShopFixtures.unique(), """
                {"eventId":"%s","eventType":"%s","aggregateType":"PREORDER",
                 "aggregateId":"00000000-0000-7000-8000-000000000001",
                 "occurredAt":"2026-09-03T01:00:03.470Z","payload":{}}
                """.formatted(ShopFixtures.unique(), eventType), 5, null, null));
    }

    private static double waitingDeadLetters(SimpleMeterRegistry gauges, String eventType, String reason) {
        Gauge gauge = gauges.find(StateGauges.WAITING_DEAD_LETTERS_BY_TYPE_METRIC).tag("eventType", eventType)
                .tag("reason", reason).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private static double deadLetterJobs(SimpleMeterRegistry gauges, String jobType) {
        return gauges.get(StateGauges.DEAD_LETTER_JOBS_METRIC).tag("jobType", jobType).gauge().value();
    }
}
