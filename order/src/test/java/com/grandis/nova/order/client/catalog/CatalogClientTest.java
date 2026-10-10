package com.grandis.nova.order.client.catalog;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.BearerTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 실제 계약(contracts/order-internal.md 의 GET /internal/options)의 경로 · 파라미터 · 헤더 · 응답을 그대로 읽는지, 오류를 어떻게 가르는지.
 * 아래 JSON 은 catalog OptionLookupView 를 ApiResponse { items } 로 감싼 모양을 복제한 것이다(catalog 는 order 의 의존성이 아니다).
 */
class CatalogClientTest {

    static final UUID FIRST = UUID.fromString("0199a3f2-8a11-7c22-8d33-5e6f70819203");
    static final UUID SECOND = UUID.fromString("0199a3f2-8a11-7c22-8d33-5e6f70819204");
    // 전달할 액세스 토큰 자리(접두어 없음). 실제 토큰 모양이 아니다
    static final String SESSION = "order-catalog-client-test-user";

    MockRestServiceServer server;
    CircuitBreakerRegistry circuitBreakers;
    BulkheadRegistry bulkheads;
    CatalogReader reader;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog");
        server = MockRestServiceServer.bindTo(builder).build();
        CatalogClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(CatalogClient.class);
        circuitBreakers = CircuitBreakerRegistry.ofDefaults();
        bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        reader = new CatalogReader(client, circuitBreakers, bulkheads);
    }

    @Test
    @DisplayName("옵션 id 를 ids 로 보내고 사용자 토큰을 Authorization: Bearer 로 싣는다 — 응답 items 를 옵션 id 로 읽는다")
    void sendsIdsAndTokenAndReadsItems() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://catalog/internal/options?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("ids", FIRST.toString(), SECOND.toString()))
                .andExpect(header(BearerTokens.HEADER, BearerTokens.value(SESSION)))
                .andRespond(withSuccess("""
                        {"success":true,
                         "data":{"items":[
                           {"optionId":"%s","productId":"0199a3f2-8a10-7b21-9c32-4d5e6f708192","productTitle":"아이폰 17",
                            "optionTitle":"블랙 / 256GB","sku":"BLK-256","price":1250000,"optionStatus":"ACTIVE","saleMode":"IN_STOCK",
                            "productStatus":"ACTIVE","visible":true,"registrationCompleted":true,
                            "warranty":{"offered":true,"surcharge":199000},"imageUrl":"https://img.example/black-0.jpg"}
                         ]},
                         "error":null,"timestamp":"2026-10-09T06:00:00Z","traceId":"t-1"}
                        """.formatted(FIRST), MediaType.APPLICATION_JSON));

        Map<UUID, CatalogOption> found = reader.find(List.of(FIRST, SECOND), SESSION);

        assertThat(found).containsOnlyKeys(FIRST);
        CatalogOption option = found.get(FIRST);
        assertThat(option.price()).isEqualByComparingTo("1250000");
        assertThat(option.saleMode()).isEqualTo("IN_STOCK");
        assertThat(option.visible()).isTrue();
        assertThat(option.registrationCompleted()).isTrue();
        assertThat(option.warranty()).isEqualTo(new CatalogOption.Warranty(true, new BigDecimal("199000")));
        assertThat(option.imageUrl()).isEqualTo("https://img.example/black-0.jpg");
        server.verify();
    }

    @Test
    @DisplayName("빈 묶음이면 부르지 않는다")
    void emptyIdsAreNotSent() {
        assertThat(reader.find(List.of(), SESSION)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("catalog 가 토큰을 거절하면(401) UNAUTHENTICATED")
    void unauthorizedIsUnauthenticated() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://catalog/internal/options"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> reader.find(List.of(FIRST), SESSION))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("5xx 는 DEPENDENCY_UNAVAILABLE(503) — 장바구니는 바꾸지 않는다")
    void serverErrorIsUnavailable() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://catalog/internal/options"))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> reader.find(List.of(FIRST), SESSION))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    @DisplayName("400 · 403 · 404 는 연동 오류(500) — '없음' 으로 삼키면 모든 장바구니가 판매 종료로 보인다")
    void otherClientErrorsAreIntegrationErrors() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)) {
            server.reset();
            server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://catalog/internal/options"))).andRespond(withStatus(status));

            assertThatThrownBy(() -> reader.find(List.of(FIRST), SESSION)).as(status.toString())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("catalog 연동 오류");
        }
    }

    @Test
    @DisplayName("본문이 계약과 다르면(읽을 수 없음) 연동 오류(500)")
    void unreadableBodyIsIntegrationError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://catalog/internal/options")))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> reader.find(List.of(FIRST), SESSION))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("catalog 연동 오류");
    }

    @Test
    @DisplayName("판정 · 금액 칸이 빠진 옵션 · 묻지 않은 옵션은 연동 오류(500) — 기본값으로 읽으면 가격 0 · 보증가 0 · 엉뚱한 거절 사유가 된다")
    void incompleteOrUnrequestedOptionIsIntegrationError() {
        Map<String, String> broken = new LinkedHashMap<>();
        broken.put("price 없음", item(FIRST).replace("\"price\":1250000", "\"price\":null"));
        broken.put("saleMode 없음", item(FIRST).replace("\"saleMode\":\"IN_STOCK\"", "\"saleMode\":null"));
        broken.put("productStatus 없음", item(FIRST).replace("\"productStatus\":\"ACTIVE\"", "\"productStatus\":null"));
        broken.put("visible 없음", item(FIRST).replace("\"visible\":true", "\"visible\":null"));
        broken.put("warranty 없음", item(FIRST).replace("\"warranty\":{\"offered\":true,\"surcharge\":199000}", "\"warranty\":null"));
        broken.put("보증 제공인데 추가금 없음", item(FIRST).replace("\"surcharge\":199000", "\"surcharge\":null"));
        broken.put("묻지 않은 옵션", item(SECOND));
        for (Map.Entry<String, String> entry : broken.entrySet()) {
            server.reset();
            server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://catalog/internal/options")))
                    .andRespond(withSuccess(envelope(entry.getValue()), MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> reader.find(List.of(FIRST), SESSION)).as(entry.getKey())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("catalog 연동 오류");
        }

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://catalog/internal/options")))
                .andRespond(withSuccess(envelope(item(FIRST)), MediaType.APPLICATION_JSON));
        assertThat(reader.find(List.of(FIRST), SESSION)).as("대조군 — 고치지 않은 같은 줄은 읽힌다").containsOnlyKeys(FIRST);
    }

    @Test
    @DisplayName("회로가 열렸거나 동시 호출 상한이 차면 catalog 를 부르지 않고 DEPENDENCY_UNAVAILABLE(503)")
    void openCircuitOrFullBulkheadIsUnavailableWithoutCalling() {
        circuitBreakers.circuitBreaker(CatalogReader.DEPENDENCY).transitionToOpenState();
        assertThatThrownBy(() -> reader.find(List.of(FIRST), SESSION))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        circuitBreakers.circuitBreaker(CatalogReader.DEPENDENCY).reset();

        Bulkhead bulkhead = bulkheads.bulkhead(CatalogReader.DEPENDENCY);
        bulkhead.acquirePermission();
        try {
            assertThatThrownBy(() -> reader.find(List.of(FIRST), SESSION))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        } finally {
            bulkhead.onComplete();
        }
        server.verify();   // 기대한 요청이 없다 — 두 번 다 부르지 않았다

        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://catalog/internal/options")))
                .andRespond(withSuccess(envelope(item(FIRST)), MediaType.APPLICATION_JSON));
        assertThat(reader.find(List.of(FIRST), SESSION)).as("대조군 — 닫히고 비면 부른다").containsOnlyKeys(FIRST);
    }

    private static String item(UUID optionId) {
        return """
                {"optionId":"%s","productId":"0199a3f2-8a10-7b21-9c32-4d5e6f708192","productTitle":"아이폰 17",
                 "optionTitle":"블랙 / 256GB","sku":"BLK-256","price":1250000,"optionStatus":"ACTIVE","saleMode":"IN_STOCK",
                 "productStatus":"ACTIVE","visible":true,"registrationCompleted":true,
                 "warranty":{"offered":true,"surcharge":199000},"imageUrl":null}""".formatted(optionId);
    }

    private static String envelope(String item) {
        return """
                {"success":true,"data":{"items":[%s]},"error":null,"timestamp":"2026-10-09T06:00:00Z","traceId":"t-1"}
                """.formatted(item);
    }
}
