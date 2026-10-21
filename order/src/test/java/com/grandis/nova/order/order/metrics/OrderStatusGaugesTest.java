package com.grandis.nova.order.order.metrics;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.support.GeneralLog;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배송 대기 주문 수 지표 — 실제 주문 수와 같고, 관리 포트의 /actuator/prometheus 로 나가며(이름 · 태그는 Grafana 규칙이 쓴다), 갱신은
 * 트랜잭션 없이 문장 하나로 상태로 시작하는 색인만 읽는다. 다른 시험의 주문이 같은 DB 에 남아 있으므로 갱신 전후의 차이와 DB 의 수를 함께 본다.
 */
@OrderIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
class OrderStatusGaugesTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired OrderStatusGauges gauges;
    @Autowired MeterRegistry registry;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderLedger ledger;
    @Autowired PlatformTransactionManager transactionManager;

    @Value("${local.management.port}")
    int managementPort;

    OrderFixtures fixtures;
    PlacedOrders placed;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        placed = new PlacedOrders(ledger, new TransactionTemplate(transactionManager), fixtures);
    }

    @Test
    void 상품_준비와_출고_대기만_실제_주문_수대로_센다() {
        gauges.refresh();
        double preparing = gauge("PREPARING_ITEMS");
        double ready = gauge("READY_TO_SHIP");

        order("PREPARING_ITEMS");
        order("PREPARING_ITEMS");
        order("READY_TO_SHIP");
        order("DELIVERED");
        order("AWAITING_CONFIRMATION");
        gauges.refresh();

        assertThat(gauge("PREPARING_ITEMS")).isEqualTo(preparing + 2).isEqualTo(count("PREPARING_ITEMS"));
        assertThat(gauge("READY_TO_SHIP")).isEqualTo(ready + 1).isEqualTo(count("READY_TO_SHIP"));
        assertThat(registry.find(OrderStatusGauges.METRIC).gauges()).as("다른 상태는 세지 않는다").hasSize(2);
    }

    @Test
    void 관리_포트의_prometheus_에_상태별로_나간다() throws Exception {
        order("READY_TO_SHIP");
        gauges.refresh();

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("order_status_count{status=\"PREPARING_ITEMS\"}")
                .contains("order_status_count{status=\"READY_TO_SHIP\"} " + (double) count("READY_TO_SHIP"));
    }

    @Test
    void 갱신은_트랜잭션_없이_문장_하나이고_상태로_시작하는_색인만_읽어_센다() throws Exception {
        List<String> statements = GeneralLog.statements("%from orders%group by%", gauges::refresh);

        assertThat(statements).as("트랜잭션 준비 · 정리 없이 세는 문장 하나").singleElement()
                .satisfies(sql -> assertThat(GeneralLog.isTransactionHousekeeping(sql)).isFalse());
        Map<String, Object> plan = jdbcTemplate.queryForMap("EXPLAIN " + statements.getFirst());
        // 상태로 시작하는 색인이 둘이다(결제 기한 · 만든 시각) — 어느 쪽이든 그 앞 칸만 읽고 행은 읽지 않는다
        assertThat(plan.get("key")).isIn("ix_order_due", "ix_order_status_created");
        assertThat((String) plan.get("Extra")).contains("Using index");
    }

    private void order(String status) {
        UUID orderId = placed.place(fixtures.customer()).id();
        fixtures.forceStatus(orderId, status);
    }

    private double gauge(String status) {
        return registry.get(OrderStatusGauges.METRIC).tag("status", status).gauge().value();
    }

    private long count(String status) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE status = ?", Long.class, status);
    }
}
