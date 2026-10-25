package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.order.OrderErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * 실제 계약(payment InternalRefundAdminController)의 경로 · 헤더 · 쿼리 · 본문 · 응답을 그대로 쓰고 읽는지, 실패를 어떻게 가르는지.
 *
 * 계약 대조: 아래 JSON 은 payment RefundIssueResponse · RefundAdmin.RefundAdminResult(ApiResponse 봉투)를 복제한 것이다. payment 쪽 필드가
 * 바뀌면 이 예시도 같이 바꾼다.
 */
class PaymentRefundAdminTest {

    static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    static final UUID TX_ID = UUID.fromString("00000000-0000-7000-8000-0000000000b1");
    static final String SESSION = "payment-refund-admin-test-admin";
    static final String ISSUE = """
            {"orderId":"%s","reason":"REFUND_REJECTED","paymentStatus":"SUCCEEDED",
             "refund":{"transactionId":"%s","status":"FAILED","attemptCount":1,"requestedAt":"2026-10-24T01:00:00Z",
                       "finishedAt":"2026-10-24T01:00:05Z","escalatedAt":null,"lastErrorCode":"NOT_CANCELABLE_PAYMENT",
                       "lastErrorMessage":"취소할 수 없는 결제","createdAt":"2026-10-24T01:00:00Z"},
             "actions":["RETRY","MANUAL_COMPLETE"]}""".formatted(ORDER_ID, TX_ID);

    MockRestServiceServer server;
    PaymentRefundAdmin refunds;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://payment");
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentRefundAdminClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build().createClient(PaymentRefundAdminClient.class);
        refunds = new PaymentRefundAdmin(client);
    }

    @Test
    void 목록은_주문_조건과_기준_시간과_관리자_토큰을_싣고_판정을_읽는다() {
        server.expect(requestTo("http://payment/internal/refund-issues?staleHours=2")).andExpect(method(HttpMethod.GET))
                .andExpect(forwardedToken()).andRespond(ok("[" + ISSUE + "]"));
        server.expect(requestTo("http://payment/internal/refund-issues?orderIds=" + ORDER_ID + "&staleHours=2"))
                .andExpect(forwardedToken()).andRespond(ok("[]"));

        assertThat(refunds.issues(List.of(), 2, SESSION)).singleElement().satisfies(issue -> {
            assertThat(issue.orderId()).isEqualTo(ORDER_ID);
            assertThat(issue.reason()).isEqualTo("REFUND_REJECTED");
            assertThat(issue.refund().transactionId()).isEqualTo(TX_ID);
            assertThat(issue.refund().lastErrorCode()).isEqualTo("NOT_CANCELABLE_PAYMENT");
            assertThat(issue.actions()).containsExactly("RETRY", "MANUAL_COMPLETE");
        });
        assertThat(refunds.issues(List.of(ORDER_ID), 2, SESSION)).isEmpty();
        server.verify();
    }

    @Test
    void 다시_보내기와_직접_환불_기록은_계약의_경로와_본문으로_부른다() {
        PaymentRefundAdminRequest request = new PaymentRefundAdminRequest(TX_ID, "카드사 확인");
        server.expect(requestTo("http://payment/internal/refunds/" + ORDER_ID + "/retry")).andExpect(method(HttpMethod.POST))
                .andExpect(forwardedToken())
                .andExpect(content().json("{\"expectedTransactionId\":\"%s\",\"note\":\"카드사 확인\"}".formatted(TX_ID),
                        JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body(envelope("{\"transactionId\":\"%s\",\"status\":\"PENDING\",\"refundedAt\":null}".formatted(TX_ID))));
        server.expect(requestTo("http://payment/internal/refunds/" + ORDER_ID + "/manual-complete"))
                .andExpect(method(HttpMethod.POST)).andExpect(forwardedToken())
                .andRespond(ok("{\"transactionId\":\"%s\",\"status\":\"SUCCEEDED\",\"refundedAt\":\"2026-10-24T05:00:00Z\"}"
                        .formatted(TX_ID)));

        assertThat(refunds.retry(ORDER_ID, request, SESSION)).isEqualTo(new PaymentRefundAdminResult(TX_ID, "PENDING", null));
        assertThat(refunds.manualComplete(ORDER_ID, request, SESSION).refundedAt())
                .isEqualTo(Instant.parse("2026-10-24T05:00:00Z"));
        server.verify();
    }

    // payment 의 환불 조치 업무 오류는 관리자가 가를 수 있게 같은 코드 · 상태 · details 로 넘긴다 — 결제사 조회 불명(503)도
    @ParameterizedTest
    @EnumSource(value = OrderErrorCode.class, names = {"REFUND_NOT_FOUND", "REFUND_ALREADY_COMPLETED", "REFUND_IN_PROGRESS",
            "REFUND_STATE_CHANGED", "REFUND_NOT_RETRYABLE", "REFUND_NOT_CANCELED_AT_PROVIDER", "REFUND_PROVIDER_UNAVAILABLE"})
    void 환불_조치_업무_오류는_같은_코드와_details_로_넘긴다(OrderErrorCode code) {
        server.expect(requestTo("http://payment/internal/refunds/" + ORDER_ID + "/retry"))
                .andRespond(rejection(HttpStatus.valueOf(code.status()), code.name(), "{\"reason\":\"X\"}"));

        assertThat(catchThrowable(() -> refunds.retry(ORDER_ID, new PaymentRefundAdminRequest(TX_ID, "n"), SESSION)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(code);
                    assertThat(e.details()).isEqualTo(Map.of("reason", "X"));
                });
    }

    @Test
    void 상태와_코드가_맞지_않거나_모르는_코드의_5xx_는_연동_장애다() {
        server.expect(requestTo("http://payment/internal/refunds/" + ORDER_ID + "/retry"))
                .andRespond(rejection(HttpStatus.SERVICE_UNAVAILABLE, "REFUND_NOT_FOUND", "null"));
        server.expect(requestTo("http://payment/internal/refunds/" + ORDER_ID + "/retry"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertUnavailable(catchThrowable(() -> refunds.retry(ORDER_ID, new PaymentRefundAdminRequest(TX_ID, "n"), SESSION)));
        assertUnavailable(catchThrowable(() -> refunds.retry(ORDER_ID, new PaymentRefundAdminRequest(TX_ID, "n"), SESSION)));
    }

    @Test
    void 전달_토큰_거절과_응답_없음은_503_이다() {
        server.expect(requestTo("http://payment/internal/refund-issues?staleHours=1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("http://payment/internal/refund-issues?staleHours=1"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertUnavailable(catchThrowable(() -> refunds.issues(List.of(), 1, SESSION)));
        assertUnavailable(catchThrowable(() -> refunds.issues(List.of(), 1, SESSION)));
    }

    private static RequestMatcher forwardedToken() {
        return request -> assertThat(BearerTokens.parse(request.getHeaders().getFirst(BearerTokens.HEADER)))
                .contains(SESSION);
    }

    private static ResponseCreator ok(String data) {
        return withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(envelope(data));
    }

    private static ResponseCreator rejection(HttpStatus status, String code, String details) {
        return withStatus(status).contentType(MediaType.APPLICATION_JSON).body("""
                {"success":false,"data":null,"error":{"code":"%s","message":"m","details":%s},
                 "timestamp":"2026-10-04T01:00:00Z","traceId":null}""".formatted(code, details));
    }

    private static String envelope(String data) {
        return """
                {"success":true,"data":%s,"error":null,"timestamp":"2026-10-04T01:00:00Z","traceId":null}""".formatted(data);
    }

    private static void assertUnavailable(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }
}
