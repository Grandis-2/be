package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.ErrorCode;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.OrderToken;
import com.grandis.nova.order.order.vo.ShipTo;
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
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 실제 계약(payment InternalPaymentAttemptController#confirm)의 경로 · 헤더 · 본문 · 응답을 그대로 쓰고 읽는지, 실패를
 * "주문을 되돌려도 되는가" 로 가르는지 — 되돌림은 결제창이 영영 시작될 수 없다는 업무 코드일 때뿐이다.
 *
 * 계약 대조: 아래 JSON 은 payment ConfirmRequest · ConfirmResponse · 오류 봉투를 복제한 것이다. payment 쪽이 바뀌면 같이 바꾼다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentConfirmerTest {

    static final String PROVIDER_ORDER_ID = "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f";
    static final String CONFIRM_URL = "http://payment/internal/payment-attempts/" + PROVIDER_ORDER_ID + "/confirm";
    // 결제창이 돌려준 결제 키 자리. 로그 검사에도 쓴다. 실제 키 모양이 아니다.
    static final String PAYMENT = "tgen_confirmer_test_20261002";
    // 전달할 액세스 토큰 자리(접두어 없음). 실제 토큰 모양이 아니다.
    static final String SESSION = "payment-confirm-test-user-7";
    static final Order ORDER = new Order(81L, OrderToken.issue(), 7L, OrderSource.PREORDER, 5L,
            "9f1c2d3e-0000-4000-8000-000000000001", OrderStatus.AUTHORIZING, PROVIDER_ORDER_ID,
            new Money(new BigDecimal("1250000")), null, null,
            new ShipTo("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null), null, 2, Instant.EPOCH,
            Instant.EPOCH);

    MockRestServiceServer server;
    PaymentConfirmer confirmer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://payment");
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentConfirmClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build().createClient(PaymentConfirmClient.class);
        confirmer = new PaymentConfirmer(client);
    }

    // 대상 · 금액은 주문에서 읽는다(D15) — 결제창 번호는 경로, 사용자 토큰은 그대로 전달
    @Test
    void sendsOrderAndStoredTotalWithForwardedToken(CapturedOutput output) {
        server.expect(requestTo(CONFIRM_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(BearerTokens.parse(request.getHeaders().getFirst(BearerTokens.HEADER)))
                        .contains(SESSION))
                .andExpect(content().json("""
                        {"targetType":"ORDER","targetId":81,"paymentKey":"%s","amount":1250000,"startAllowed":true,"reserve":false}
                        """.formatted(PAYMENT), JsonCompareMode.STRICT))
                .andRespond(reply("APPROVED", null));

        assertThat(confirm()).isEqualTo(new PaymentConfirmation.Approved());
        server.verify();
        assertThat(output.getAll()).doesNotContain(PAYMENT).doesNotContain(SESSION);
    }

    @Test
    void readsDeclineAndPending() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(reply("DECLINED", "PAYMENT_EXPIRED"));
        server.expect(requestTo(CONFIRM_URL)).andRespond(reply("PENDING", null));

        assertThat(confirm()).isEqualTo(new PaymentConfirmation.Declined(DeclineReason.PAYMENT_EXPIRED));
        assertThat(confirm()).isEqualTo(new PaymentConfirmation.Pending());
    }

    // 결과 회수만: 시작 금지 플래그를 싣는다
    @Test
    void recoverOnlySendsStartNotAllowed() {
        server.expect(requestTo(CONFIRM_URL))
                .andExpect(content().json("{\"startAllowed\":false,\"reserve\":false}", JsonCompareMode.LENIENT))
                .andRespond(reply("PENDING", null));

        assertThat(confirmer.confirm(ORDER, PROVIDER_ORDER_ID, PAYMENT, SESSION, false))
                .isEqualTo(new PaymentConfirmation.Pending());
        server.verify();
    }

    // ── 앞으로도 시작될 수 없다(업무 코드): 주문을 되돌려도 된다 ─────────────────────

    @Test
    void attemptNotFoundIsNotStartable() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(rejected(HttpStatus.NOT_FOUND, "PAYMENT_ATTEMPT_NOT_FOUND"));

        assertNotStartable(confirm(), OrderErrorCode.PAYMENT_ATTEMPT_NOT_FOUND);
    }

    @Test
    void amountMismatchIsNotStartable(CapturedOutput output) {
        server.expect(requestTo(CONFIRM_URL)).andRespond(rejected(HttpStatus.CONFLICT, "PAYMENT_AMOUNT_MISMATCH"));

        assertNotStartable(confirm(), OrderErrorCode.PAYMENT_AMOUNT_MISMATCH);
        assertThat(output.getAll()).contains("ERROR");
    }

    @Test
    void unsupportedTargetIsNotStartableIntegrationError() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(rejected(HttpStatus.BAD_REQUEST, "PAYMENT_TARGET_UNSUPPORTED"));

        assertThat(confirm()).isInstanceOfSatisfying(PaymentConfirmation.NotStartable.class, notStartable ->
                assertThat(notStartable.failure()).isInstanceOf(IllegalStateException.class));
    }

    // ── 이번 요청만 거절 · 닿지 못함: 앞선 요청이 이미 시작했을 수 있어 되돌리지 않는다 ─────────────

    // 교착 — 이번 요청은 시작하지 않았지만 동시 요청이 시작했을 수 있다
    @Test
    void startConflictIsUnanswered() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(rejected(HttpStatus.CONFLICT, "PAYMENT_START_CONFLICT"));

        assertUnanswered(confirm(), CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    // order 의 필터가 방금 통과시킨 토큰이다 — payment 쪽 사정으로 보고 503(사용자 401 아님)
    @Test
    void rejectedTokenIsUnanswered(CapturedOutput output) {
        server.expect(requestTo(CONFIRM_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertUnanswered(confirm(), CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        assertThat(output.getAll()).contains("전달 토큰 거절(401)");
    }

    // 본문 없는 4xx(앞단 · 라우팅) · 계약 밖 코드는 결제창에 대한 확언이 아니다 — 연동 오류(500)로 답하고 되돌리지 않는다
    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "FORBIDDEN", "NOT_FOUND", "CONFLICT"})
    void clientErrorWithoutBusinessCodeIsUnanswered(HttpStatus status) {
        server.expect(requestTo(CONFIRM_URL)).andRespond(withStatus(status));
        server.expect(requestTo(CONFIRM_URL)).andRespond(rejected(status, "VALIDATION_FAILED"));

        for (int i = 0; i < 2; i++) {
            assertThat(confirm()).isInstanceOfSatisfying(PaymentConfirmation.Unanswered.class, unanswered ->
                    assertThat(unanswered.failure()).isInstanceOf(IllegalStateException.class));
        }
    }

    @Test
    void connectFailureIsUnanswered() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withException(new HttpConnectTimeoutException("HTTP connect timed out")));

        assertUnanswered(confirm(), CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        assertUnanswered(confirm(), CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    // ── 모른다: 주문을 승인 중으로 둔다 ──────────────────────────────────────────

    @Test
    void readTimeoutIsPending() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(confirm()).isEqualTo(new PaymentConfirmation.Pending());
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"INTERNAL_SERVER_ERROR", "BAD_GATEWAY", "SERVICE_UNAVAILABLE"})
    void serverErrorIsPending(HttpStatus status) {
        server.expect(requestTo(CONFIRM_URL)).andRespond(withStatus(status));

        assertThat(confirm()).isEqualTo(new PaymentConfirmation.Pending());
    }

    // 200 인데 계약과 다르면 시작했을 수 있다 — 되돌리지 않는다
    @Test
    void malformedSuccessIsPending(CapturedOutput output) {
        server.expect(requestTo(CONFIRM_URL)).andRespond(reply("DECLINED", null));
        server.expect(requestTo(CONFIRM_URL)).andRespond(withSuccess("{\"success\":true,\"data\":null}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(CONFIRM_URL)).andRespond(withSuccess("{\"success\":true,\"data\":{\"result\":7",
                MediaType.APPLICATION_JSON));

        assertThat(confirm()).isEqualTo(new PaymentConfirmation.Pending());
        assertThat(confirm()).isEqualTo(new PaymentConfirmation.Pending());
        assertThat(confirm()).isEqualTo(new PaymentConfirmation.Pending());
        assertThat(output.getAll()).contains("계약과 다른 응답");
    }

    // ── 결제창 확인(승인 중으로 바꾸기 전): 시작하지 않으므로 답이 없으면 늘 "이번 요청 실패" ───────────────

    // 확인은 시작 금지 + 결제창 확보다 — payment 의 만료가 확인과 승인 중 전환 사이에 끼지 않게
    @Test
    void checkSendsStartNotAllowedAndReadsState() {
        server.expect(requestTo(CONFIRM_URL))
                .andExpect(content().json("{\"startAllowed\":false,\"reserve\":true}", JsonCompareMode.LENIENT))
                .andRespond(reply("PENDING", null));
        server.expect(requestTo(CONFIRM_URL)).andRespond(reply("DECLINED", "PAYMENT_EXPIRED"));

        assertThat(check()).isEqualTo(new PaymentConfirmation.Pending());
        assertThat(check()).isEqualTo(new PaymentConfirmation.Declined(DeclineReason.PAYMENT_EXPIRED));
        server.verify();
    }

    @Test
    void checkOfUnknownAttemptIsNotStartable() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(rejected(HttpStatus.NOT_FOUND, "PAYMENT_ATTEMPT_NOT_FOUND"));

        assertNotStartable(check(), OrderErrorCode.PAYMENT_ATTEMPT_NOT_FOUND);
    }

    // 시작 호출에서는 읽기 기한 · 5xx 가 "확인 중"이지만, 확인에서는 그렇게 두면 모르는 번호로 승인 중이 될 수 있다
    @Test
    void checkWithoutAnswerIsUnanswered() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(requestTo(CONFIRM_URL)).andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(CONFIRM_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(CONFIRM_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertUnanswered(check(), CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        assertUnanswered(check(), CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        assertUnanswered(check(), CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        assertUnanswered(check(), CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void malformedCheckAnswerIsUnansweredIntegrationError(CapturedOutput output) {
        server.expect(requestTo(CONFIRM_URL)).andRespond(reply("DECLINED", null));

        assertThat(check()).isInstanceOfSatisfying(PaymentConfirmation.Unanswered.class, unanswered ->
                assertThat(unanswered.failure()).isInstanceOf(IllegalStateException.class));
        assertThat(output.getAll()).contains("계약과 다른 응답");
    }

    @Test
    void requestToStringMasksPaymentKey() {
        assertThat(ConfirmRequest.of(ORDER, PAYMENT, true).toString()).doesNotContain(PAYMENT).contains("1250000");
    }

    private PaymentConfirmation confirm() {
        return confirmer.confirm(ORDER, PROVIDER_ORDER_ID, PAYMENT, SESSION, true);
    }

    private PaymentConfirmation check() {
        return confirmer.check(ORDER, PROVIDER_ORDER_ID, PAYMENT, SESSION);
    }

    private static ResponseCreator reply(String result, String declineReason) {
        String reason = declineReason == null ? "null" : "\"" + declineReason + "\"";
        return withSuccess("""
                {"success":true,"data":{"result":"%s","declineReason":%s},"error":null,
                 "timestamp":"2026-10-02T00:00:00Z","traceId":null}
                """.formatted(result, reason), MediaType.APPLICATION_JSON);
    }

    private static ResponseCreator rejected(HttpStatus status, String code) {
        return withStatus(status).contentType(MediaType.APPLICATION_JSON).body("""
                {"success":false,"data":null,"error":{"code":"%s","message":"m","details":null},
                 "timestamp":"2026-10-02T00:00:00Z","traceId":null}
                """.formatted(code));
    }

    private static void assertNotStartable(PaymentConfirmation confirmation, ErrorCode expected) {
        assertThat(confirmation).isInstanceOfSatisfying(PaymentConfirmation.NotStartable.class, notStartable ->
                assertThat(notStartable.failure()).isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(expected)));
    }

    private static void assertUnanswered(PaymentConfirmation confirmation, ErrorCode expected) {
        assertThat(confirmation).isInstanceOfSatisfying(PaymentConfirmation.Unanswered.class, unanswered ->
                assertThat(unanswered.failure()).isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(expected)));
    }
}
