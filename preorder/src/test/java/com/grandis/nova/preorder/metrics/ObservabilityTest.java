package com.grandis.nova.preorder.metrics;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.accept.application.RegisterJobReady;
import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 부하 시험 · 운영에서 볼 지표가 실제로 기록되고 /actuator/prometheus 로 나가는지. 레지스트리를 공유하므로 증가분으로 본다. */
@PreorderIntegrationTest
@AutoConfigureMetrics
@AutoConfigureMockMvc
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
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

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
                new RegisterJobReady(UUID.randomUUID(), ShopFixtures.unique())));

        await().atMost(Duration.ofSeconds(5)).until(() -> publishCount() == published + 1);
    }

    @Test
    void 상태_지표는_갱신할_때_DB_에서_센다() {
        accepts.accept(fixtures.customer());
        jdbcTemplate.update("""
                INSERT INTO dead_letter_events (id, source_queue, message_id, body, failure_reason, receive_count,
                                                status, created_at, updated_at)
                VALUES (?, 'preorder-events', ?, 'not-json', 'UNREADABLE_BODY', 5, 'OPEN',
                        UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, UTC_TIMESTAMP(6))
                """, UuidBinary.toBytes(UUID.randomUUID()), ShopFixtures.unique());

        stateGauges.refresh();

        assertThat(gauge("preorder.status.count", "status", "PENDING_SYNC")).isPositive();
        assertThat(registry.get("preorder.dlq.waiting").gauge().value()).isPositive();
        assertThat(registry.get("preorder.dlq.waiting.oldest.age").gauge().value()).as("초")
                .isGreaterThanOrEqualTo(3600);
    }

    @Test
    void catalog_캐시_적중률과_지표를_prometheus_로_내보낸다() throws Exception {
        accepts.accept(fixtures.customer());

        assertThat(registry.get("cache.gets").tag("cache", "catalog.products").functionCounters()).isNotEmpty();
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("preorder_accept_seconds")))
                .andExpect(content().string(containsString("preorder_campaign_lock_wait_seconds_bucket")))
                .andExpect(content().string(containsString("preorder_status_count")))
                .andExpect(content().string(containsString("# TYPE preorder_outbox_unpublished gauge")))
                .andExpect(content().string(containsString("# TYPE preorder_outbox_unpublished_max_attempts gauge")))
                .andExpect(content().string(containsString("hikaricp_connections_pending")))
                .andExpect(content().string(containsString("resilience4j_circuitbreaker_state")))
                .andExpect(content().string(containsString("resilience4j_retry_calls")))
                .andExpect(content().string(containsString("resilience4j_bulkhead_available_concurrent_calls")));
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
}
