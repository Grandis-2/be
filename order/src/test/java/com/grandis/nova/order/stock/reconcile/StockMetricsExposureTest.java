package com.grandis.nova.order.stock.reconcile;

import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 지표가 관리 포트의 /actuator/prometheus 로 토큰 없이 나간다(수집기가 이 포트를 본다). 서비스 포트로는 열리지 않는다.
 * 실제 Tomcat 두 개에 HTTP 로 묻는다 — MockMvc 는 관리 포트를 띄우지 않는다(HealthEndpointTest 와 같다). 이름 · 접미사는 경보 규칙(nova-infra)이 쓴다.
 */
@OrderIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
class StockMetricsExposureTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    @Test
    void 관리_포트의_prometheus_에_재고_지표가_토큰_없이_나간다() throws Exception {
        HttpResponse<String> response = get(managementPort);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("order_stock_mismatch_total{")
                .contains("site=\"cart-payment\"", "site=\"unpaid-cancel\"", "site=\"cart-replace\"", "site=\"draw-sale\"",
                        "site=\"draw-release\"")
                .contains("order_stock_drift_options")
                .contains("order_stock_reconcile_last_success_timestamp_seconds");
    }

    @Test
    void 서비스_포트로는_prometheus_가_익명에게_열리지_않는다() throws Exception {
        assertThat(get(port).statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> get(int port) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
