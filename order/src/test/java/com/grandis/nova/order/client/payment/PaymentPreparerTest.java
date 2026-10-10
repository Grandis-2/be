package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.OrderToken;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * 실제 계약(payment InternalPaymentAttemptController)의 경로 · 헤더 · 본문 · 응답을 그대로 쓰고 읽는지, 실패를 어떻게 가르는지.
 *
 * 계약 대조: 아래 JSON 은 payment OpenCaptureRequest · PaymentAttemptResponse(ApiResponse 봉투)를 복제한 것이다(payment 는 order 의
 * 의존성이 아니라 직접 직렬화할 수 없다). payment 쪽 필드가 바뀌면 이 예시도 같이 바꾼다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentPreparerTest {

    static final String ATTEMPTS_URL = "http://payment/internal/payment-attempts";
    static final UUID ORDER_ID = TestIds.id(81);
    static final BigDecimal AMOUNT = new BigDecimal("1250000");
    static final String PROVIDER_ORDER_ID = "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f";
    static final PayableTarget TARGET = PayableTarget.of(new Order(ORDER_ID, OrderToken.issue(), TestIds.id(7), OrderSource.PREORDER,
            TestIds.id(5), "9f1c2d3e-0000-4000-8000-000000000001", OrderStatus.AWAITING_PAYMENT, null, new Money(AMOUNT), null, null,
            new ShipTo("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null), null, 1, Instant.EPOCH, Instant.EPOCH));
    // 전달할 액세스 토큰 자리(접두어 없음). 로그 검사에도 쓴다. 실제 토큰 모양이 아니다.
    static final String SESSION = "payment-client-test-user-7";

    MockRestServiceServer server;
    PaymentPreparer preparer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://payment");
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(PaymentClient.class);
        preparer = new PaymentPreparer(client);
    }

    // 보내는 헤더가 받는 쪽 필터의 규칙(BearerTokens.parse)으로 다시 읽히는지. 금액은 서버 값 그대로, 대상은 주문이다.
    @Test
    void opensCaptureForOrderWithForwardedToken() {
        server.expect(requestTo(ATTEMPTS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(BearerTokens.parse(request.getHeaders().getFirst(BearerTokens.HEADER)))
                        .contains(SESSION))
                .andExpect(content().json("""
                        {"targetType":"ORDER","targetId":"00000000-0000-7000-8000-000000000081","amount":1250000}
                        """, JsonCompareMode.STRICT))
                .andRespond(created(PROVIDER_ORDER_ID, "1250000"));

        PaymentAttempt attempt = preparer.openCapture(TARGET, SESSION);

        assertThat(attempt).isEqualTo(new PaymentAttempt(PROVIDER_ORDER_ID, AMOUNT));
        server.verify();
    }

    // 헤더가 없으면 빈 값으로라도 싣지 않는다 — payment 가 401 로 판단한다.
    @Test
    void missingSessionTokenIsNotSent() {
        server.expect(requestTo(ATTEMPTS_URL))
                .andExpect(headerDoesNotExist(BearerTokens.HEADER))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        catchThrowable(() -> preparer.openCapture(TARGET, null));
        server.verify();
    }

    // order 의 필터가 방금 통과시킨 토큰이다 — payment 의 거절은 payment 쪽 사정으로 보고 다시 시도를 안내한다(사용자 401 아님).
    // 설정 어긋남이면 503 이 계속된다 — 일반 장애와 가를 수 있게 ERROR 표식을 남긴다.
    @Test
    void rejectedTokenIsUnavailable(CapturedOutput output) {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertUnavailable(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)));
        assertThat(output.getAll()).contains("ERROR").contains("전달 토큰 거절(401)");
    }

    // 그 밖의 4xx 는 order 가 계약을 어긴 것이다 — 다시 불러도 같으므로 연동 오류(500).
    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "FORBIDDEN", "NOT_FOUND", "CONFLICT"})
    void otherClientErrorsAreIntegrationErrors(HttpStatus status) {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(withStatus(status));

        assertThat(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"INTERNAL_SERVER_ERROR", "SERVICE_UNAVAILABLE"})
    void serverErrorsAreUnavailable(HttpStatus status) {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(withStatus(status));

        assertUnavailable(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)));
    }

    // 타임아웃 뒤 payment 가 PENDING 을 이미 만들었을 수 있다 — 버려진 PENDING 으로 남고 사용자가 다시 부른다.
    @Test
    void timeoutIsUnavailable() {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertUnavailable(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)));
    }

    @Test
    void malformedBodyIsIntegrationError() {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\":true,\"data\":{\"amount\":\"many\""));

        assertThat(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)))
                .isInstanceOf(IllegalStateException.class);
    }

    // 결제창에 띄울 금액은 주문 총액이어야 한다. 다른 금액 · 빈 응답을 프론트에 넘기지 않는다.
    @Test
    void differentAmountIsIntegrationError() {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(created(PROVIDER_ORDER_ID, "1000"));

        assertThat(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingProviderOrderIdIsIntegrationError() {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON).body(envelope("{\"providerOrderId\":null,\"amount\":1250000}")));

        assertThat(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingDataIsIntegrationError() {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON).body(envelope("null")));

        assertThat(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)))
                .isInstanceOf(IllegalStateException.class);
    }

    // 실패를 로그로 남기는 경로(연동 오류 · 장애)에서도 전달한 토큰은 로그와 예외 메시지에 없다.
    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "SERVICE_UNAVAILABLE"})
    void sessionTokenIsNotLoggedOnFailure(HttpStatus status, CapturedOutput output) {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(withStatus(status).body("{\"success\":false}")
                .contentType(MediaType.APPLICATION_JSON));

        assertNoCredential(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)), output);
    }

    @Test
    void sessionTokenIsNotLoggedOnTimeout(CapturedOutput output) {
        server.expect(requestTo(ATTEMPTS_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertNoCredential(catchThrowable(() -> preparer.openCapture(TARGET, SESSION)), output);
    }

    private static void assertUnavailable(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    private static void assertNoCredential(Throwable thrown, CapturedOutput output) {
        assertThat(thrown).isNotNull();
        assertThat(output.getAll()).doesNotContain(SESSION);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(SESSION);
        }
    }

    private static DefaultResponseCreator created(String providerOrderId, String amount) {
        return withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body(envelope("{\"providerOrderId\":\"%s\",\"amount\":%s}".formatted(providerOrderId, amount)));
    }

    private static String envelope(String data) {
        return """
                {"success":true,"data":%s,"error":null,"timestamp":"2026-10-01T02:00:00Z","traceId":"t-1"}
                """.formatted(data);
    }
}
