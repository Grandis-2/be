package com.grandis.nova.payment.client.toss;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 요청 모양(경로 · 본문 · 멱등 키 · Basic 인증)과 응답 분류 중 분류표 밖의 것 — 성공 응답의 상태(D7) · 짝 대조 · 모르는 코드 ·
 * 타임아웃 · 연결 실패 · 읽을 수 없는 응답 — 과 시크릿이 새지 않는지. 분류표 전체는 {@link TossErrorTableTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class TossPaymentClientTest {

    static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString((TossTestClients.MERCHANT_CREDENTIAL + ":").getBytes(StandardCharsets.UTF_8));

    MockRestServiceServer server;
    TossPaymentClient client;

    @BeforeEach
    void setUp() {
        TossTestClients.Pair pair = TossTestClients.create();
        server = pair.server();
        client = pair.client();
    }

    // ── 요청 모양 ──

    @Test
    void confirmSendsBodyIdempotencyKeyAndBasicAuthorization() {
        TossStubs.expectConfirm(server)
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(header(TossIdempotencyKey.HEADER, TossStubs.DEDUP))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("""
                        {"paymentKey":"%s","orderId":"%s","amount":%d}
                        """.formatted(TossStubs.PAYMENT_REF, TossStubs.ORDER_REF, TossStubs.AMOUNT), JsonCompareMode.STRICT))
                .andRespond(TossStubs.paymentResponse("DONE"));

        client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());
        server.verify();
    }

    // 전액 취소만 쓴다 — cancelAmount 를 싣지 않는다(없으면 전액, 토스 문서).
    @Test
    void cancelSendsReasonOnlyWithIdempotencyKeyAndBasicAuthorization() {
        TossStubs.expectCancel(server)
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(header(TossIdempotencyKey.HEADER, TossStubs.DEDUP))
                .andExpect(content().json("""
                        {"cancelReason":"%s"}
                        """.formatted(TossStubs.CANCEL_REASON), JsonCompareMode.STRICT))
                .andRespond(TossStubs.paymentResponse("CANCELED"));

        client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey());
        server.verify();
    }

    // GET 은 토스가 스스로 멱등을 보장하고 멱등 키를 무시한다 — 싣지 않는다.
    @Test
    void lookupsSendBasicAuthorizationWithoutIdempotencyKey() {
        TossStubs.expectLookupByPaymentKey(server)
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(headerDoesNotExist(TossIdempotencyKey.HEADER))
                .andRespond(TossStubs.paymentResponse("DONE"));
        TossStubs.expectLookupByOrderId(server)
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(headerDoesNotExist(TossIdempotencyKey.HEADER))
                .andRespond(TossStubs.paymentResponse("DONE"));

        client.findByPaymentKey(TossStubs.PAYMENT_REF);
        client.findByOrderId(TossStubs.ORDER_REF);
        server.verify();
    }

    // ── 성공 응답 (D7) ──

    @Test
    void confirmDoneIsSucceededWithPayment() {
        TossStubs.expectConfirm(server).andRespond(TossStubs.paymentResponse("DONE"));

        TossCommandResult result = client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

        assertThat(result).isInstanceOfSatisfying(TossCommandResult.Succeeded.class, succeeded ->
                assertThat(succeeded.payment()).isEqualTo(new TossPayment(TossStubs.PAYMENT_REF, TossStubs.ORDER_REF,
                        TossPaymentStatus.DONE, TossStubs.AMOUNT, TossStubs.AMOUNT, "카드",
                        OffsetDateTime.parse("2026-09-29T15:31:12+09:00").toInstant(), "txn-approve-0001")));
    }

    // 가상계좌는 끈다(D7). 그래도 오면 승인됐다고 볼 수 없다 — 결과 불명으로 조회 · 알림에 맡긴다.
    @ParameterizedTest
    @ValueSource(strings = {"WAITING_FOR_DEPOSIT", "IN_PROGRESS", "READY", "ABORTED", "EXPIRED", "CANCELED",
            "PARTIAL_CANCELED", "SOMETHING_NEW"})
    void confirmWithStatusOtherThanDoneIsUnknown(String status, CapturedOutput output) {
        TossStubs.expectConfirm(server).andRespond(TossStubs.paymentResponse(status));

        TossCommandResult result = client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

        assertThat(result).isEqualTo(new TossCommandResult.Unknown(UnknownReason.UNEXPECTED_STATUS, null));
        assertThat(output.getAll()).contains("예상 밖 상태").contains(TossStubs.ORDER_REF);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED"})
    void cancelCanceledIsSucceeded(String status) {
        TossStubs.expectCancel(server).andRespond(TossStubs.paymentResponse(status));

        TossCommandResult result = client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey());

        assertThat(result).isInstanceOfSatisfying(TossCommandResult.Succeeded.class,
                succeeded -> assertThat(succeeded.payment().status()).isEqualTo(TossPaymentStatus.CANCELED));
    }

    // 전액 취소만 보낸다. 부분 취소 · 아직 승인 상태로 오면 환불 완료라고 볼 수 없다.
    @ParameterizedTest
    @ValueSource(strings = {"PARTIAL_CANCELED", "DONE", "WAITING_FOR_DEPOSIT", "SOMETHING_NEW"})
    void cancelWithStatusOtherThanCanceledIsUnknown(String status) {
        TossStubs.expectCancel(server).andRespond(TossStubs.paymentResponse(status));

        TossCommandResult result = client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey());

        assertThat(result).isEqualTo(new TossCommandResult.Unknown(UnknownReason.UNEXPECTED_STATUS, null));
    }

    // 조회는 상태를 해석하지 않는다 — 무엇으로 볼지는 부르는 쪽(복구 · 환불 확인)이 정한다. 모르는 상태도 읽는다.
    @ParameterizedTest
    @ValueSource(strings = {"DONE", "CANCELED", "WAITING_FOR_DEPOSIT", "ABORTED", "SOMETHING_NEW"})
    void lookupReturnsPaymentAsIs(String status) {
        TossStubs.expectLookupByPaymentKey(server).andRespond(TossStubs.paymentResponse(status));

        TossLookupResult result = client.findByPaymentKey(TossStubs.PAYMENT_REF);

        assertThat(result).isInstanceOfSatisfying(TossLookupResult.Found.class,
                found -> assertThat(found.payment().status()).isEqualTo(TossPaymentStatus.from(status)));
    }

    // status 필드가 없거나 null 이면 역직렬화가 from() 을 거치지 않는다 — null 로 두면 상태 비교가 조용히 어긋난다.
    @ParameterizedTest
    @ValueSource(strings = {"\"status\":null,", ""})
    void missingOrNullStatusIsUnrecognized(String statusField) {
        String body = TossStubs.payment(TossStubs.PAYMENT_REF, TossStubs.ORDER_REF, "DONE", TossStubs.AMOUNT,
                TossStubs.AMOUNT).replace("\"status\":\"DONE\",", statusField);
        TossStubs.expectLookupByPaymentKey(server).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.findByPaymentKey(TossStubs.PAYMENT_REF)).isInstanceOfSatisfying(TossLookupResult.Found.class,
                found -> assertThat(found.payment().status()).isEqualTo(TossPaymentStatus.UNRECOGNIZED));
        assertThat(new TossPayment(TossStubs.PAYMENT_REF, TossStubs.ORDER_REF, null, 1, 1, null, null, null).status())
                .isEqualTo(TossPaymentStatus.UNRECOGNIZED);
    }

    @Test
    void unrecognizedStatusIsReadAsUnrecognized() {
        assertThat(TossPaymentStatus.from("SOMETHING_NEW")).isEqualTo(TossPaymentStatus.UNRECOGNIZED);
        assertThat(TossPaymentStatus.from(null)).isEqualTo(TossPaymentStatus.UNRECOGNIZED);
    }

    // ── 짝 대조: 물은 결제가 아닌 응답 ──

    @Test
    void confirmResponseForAnotherOrderIsMismatched(CapturedOutput output) {
        TossStubs.expectConfirm(server).andRespond(withSuccess(TossStubs.payment(TossStubs.PAYMENT_REF,
                "another-order-0001", "DONE", TossStubs.AMOUNT, TossStubs.AMOUNT), MediaType.APPLICATION_JSON));

        TossCommandResult result = client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

        assertThat(result).isEqualTo(new TossCommandResult.Unknown(UnknownReason.MISMATCHED_RESPONSE, null));
        assertThat(output.getAll()).contains("물은 결제와 다른 응답");
    }

    @Test
    void confirmResponseForAnotherPaymentKeyIsMismatched() {
        TossStubs.expectConfirm(server).andRespond(withSuccess(TossStubs.payment("tviva-another-payment",
                TossStubs.ORDER_REF, "DONE", TossStubs.AMOUNT, TossStubs.AMOUNT), MediaType.APPLICATION_JSON));

        assertThat(client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey()))
                .isEqualTo(new TossCommandResult.Unknown(UnknownReason.MISMATCHED_RESPONSE, null));
    }

    @Test
    void cancelResponseForAnotherPaymentKeyIsMismatched() {
        TossStubs.expectCancel(server).andRespond(withSuccess(TossStubs.payment("tviva-another-payment",
                TossStubs.ORDER_REF, "CANCELED", TossStubs.AMOUNT, 0), MediaType.APPLICATION_JSON));

        assertThat(client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey()))
                .isEqualTo(new TossCommandResult.Unknown(UnknownReason.MISMATCHED_RESPONSE, null));
    }

    @Test
    void lookupResponsesForAnotherPaymentAreMismatched() {
        TossStubs.expectLookupByPaymentKey(server).andRespond(withSuccess(TossStubs.payment("tviva-another-payment",
                TossStubs.ORDER_REF, "DONE", TossStubs.AMOUNT, TossStubs.AMOUNT), MediaType.APPLICATION_JSON));
        TossStubs.expectLookupByOrderId(server).andRespond(withSuccess(TossStubs.payment(TossStubs.PAYMENT_REF,
                "another-order-0001", "DONE", TossStubs.AMOUNT, TossStubs.AMOUNT), MediaType.APPLICATION_JSON));

        assertThat(client.findByPaymentKey(TossStubs.PAYMENT_REF))
                .isEqualTo(new TossLookupResult.Unknown(UnknownReason.MISMATCHED_RESPONSE, null));
        assertThat(client.findByOrderId(TossStubs.ORDER_REF))
                .isEqualTo(new TossLookupResult.Unknown(UnknownReason.MISMATCHED_RESPONSE, null));
    }

    // ── 표에 없는 코드 · 전송 실패 · 읽을 수 없는 응답 → 결과 불명 ──

    @Test
    void unlistedCodeIsUnknownAndWarned(CapturedOutput output) {
        TossStubs.expectConfirm(server).andRespond(TossErrorTableTest.error(400, "BRAND_NEW_CODE"));
        TossStubs.expectCancel(server).andRespond(TossErrorTableTest.error(400, "BRAND_NEW_CODE"));
        TossStubs.expectLookupByPaymentKey(server).andRespond(TossErrorTableTest.error(404, "BRAND_NEW_CODE"));

        assertThat(client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey()))
                .isEqualTo(new TossCommandResult.Unknown(UnknownReason.UNLISTED_CODE, "BRAND_NEW_CODE"));
        assertThat(client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey()))
                .isEqualTo(new TossCommandResult.Unknown(UnknownReason.UNLISTED_CODE, "BRAND_NEW_CODE"));
        assertThat(client.findByPaymentKey(TossStubs.PAYMENT_REF))
                .isEqualTo(new TossLookupResult.Unknown(UnknownReason.UNLISTED_CODE, "BRAND_NEW_CODE"));
        assertThat(output.getAll().lines().filter(line -> line.contains("WARN") && line.contains("표에 없는 코드")
                && line.contains("BRAND_NEW_CODE"))).hasSize(3);
    }

    // 표에 있는 코드는 HTTP 상태와 무관하게 표대로 간다(D8). 문서와 다른 상태로 와도 같다.
    @Test
    void classificationIgnoresHttpStatus() {
        TossStubs.expectConfirm(server).andRespond(TossErrorTableTest.error(500, "REJECT_CARD_PAYMENT"));

        assertThat(client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey()))
                .isInstanceOf(TossCommandResult.Rejected.class);
    }

    @Test
    void readTimeoutIsUnknown() {
        expectAll(withException(new SocketTimeoutException("Read timed out")));

        assertAllUnknown(UnknownReason.TIMEOUT);
    }

    @Test
    void connectionFailureIsUnknown() {
        expectAll(withException(new ConnectException("Connection refused")));

        assertAllUnknown(UnknownReason.CONNECTION_FAILURE);
    }

    @Test
    void otherIoFailureIsUnknown() {
        expectAll(withException(new IOException("Connection reset")));

        assertAllUnknown(UnknownReason.CONNECTION_FAILURE);
    }

    // 2xx 인데 읽을 수 없다 — 승인 · 취소가 됐을 수 있다. 실패로 보지 않는다. 계약이 바뀐 것이면 조회도 못 읽으므로 ERROR 다.
    @Test
    void unreadableSuccessBodyIsUnknown(CapturedOutput output) {
        expectAll(withSuccess("{\"paymentKey\":", MediaType.APPLICATION_JSON));

        assertAllUnknown(UnknownReason.UNREADABLE_RESPONSE);
        assertThat(output.getAll().lines().filter(line -> line.contains("ERROR") && line.contains("2xx 응답을 읽을 수 없음")))
                .hasSize(4);
    }

    // 성공 상태인데 본문이 없다 — 계약 위반이라 ERROR(조회도 같은 문제를 겪을 수 있다).
    @Test
    void emptySuccessBodyIsUnknownAndLoggedAsError(CapturedOutput output) {
        expectAll(withSuccess());

        assertAllUnknown(UnknownReason.UNREADABLE_RESPONSE);
        assertThat(output.getAll().lines().filter(line -> line.contains("ERROR") && line.contains("빈 2xx 응답 본문")))
                .hasSize(4);
    }

    /*
     * RestClient 는 본문 변환 중 IOException · HttpMessageNotReadableException 만 RestClientException 으로 감싼다. 그 밖의
     * HttpMessageConversionException(예: Jackson 타입 정의 오류)은 그대로 올라온다 — 잡지 않으면 "예외 대신 결과" 계약이 깨지고,
     * 승인이 이미 된 뒤라면 결과 없이 호출이 끝난다.
     */
    @Test
    void conversionExceptionOutsideRestClientExceptionIsUnknown(CapturedOutput output) {
        TossPaymentsApi api = org.mockito.Mockito.mock(TossPaymentsApi.class);
        HttpMessageConversionException conversion = new HttpMessageConversionException("Type definition error");
        org.mockito.BDDMockito.given(api.confirm(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .willThrow(conversion);
        org.mockito.BDDMockito.given(api.cancel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).willThrow(conversion);
        org.mockito.BDDMockito.given(api.getByPaymentKey(org.mockito.ArgumentMatchers.any())).willThrow(conversion);
        org.mockito.BDDMockito.given(api.getByOrderId(org.mockito.ArgumentMatchers.any())).willThrow(conversion);
        TossPaymentClient direct = new TossPaymentClient(api);

        assertThat(direct.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey()))
                .isEqualTo(new TossCommandResult.Unknown(UnknownReason.UNREADABLE_RESPONSE, null));
        assertThat(direct.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey()))
                .isEqualTo(new TossCommandResult.Unknown(UnknownReason.UNREADABLE_RESPONSE, null));
        assertThat(direct.findByPaymentKey(TossStubs.PAYMENT_REF))
                .isEqualTo(new TossLookupResult.Unknown(UnknownReason.UNREADABLE_RESPONSE, null));
        assertThat(direct.findByOrderId(TossStubs.ORDER_REF))
                .isEqualTo(new TossLookupResult.Unknown(UnknownReason.UNREADABLE_RESPONSE, null));
        assertThat(output.getAll().lines().filter(line -> line.contains("ERROR") && line.contains("2xx 응답을 읽을 수 없음")))
                .hasSize(4);
    }

    // 결과 불명은 사유와 관계없이 WARN 이다 — 표의 불명 코드도.
    @Test
    void listedUnknownCodeIsWarned(CapturedOutput output) {
        TossStubs.expectConfirm(server).andRespond(TossErrorTableTest.error(400, "ALREADY_PROCESSED_PAYMENT"));

        client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

        assertThat(output.getAll().lines().filter(line -> line.contains("WARN") && line.contains("표의 불명 코드")
                && line.contains("ALREADY_PROCESSED_PAYMENT"))).hasSize(1);
    }

    // 게이트웨이 오류 페이지(HTML) · 본문 없는 5xx · 코드 없는 오류 본문 — 토스가 처리했는지 모른다.
    @ParameterizedTest
    @ValueSource(strings = {"html", "empty", "no-code"})
    void errorWithoutTossCodeIsUnknown(String shape) {
        expectAll(switch (shape) {
            case "html" -> withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html>502</html>");
            case "empty" -> withStatus(HttpStatus.GATEWAY_TIMEOUT);
            default -> withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"no code\"}");
        });

        assertAllUnknown(UnknownReason.UNREADABLE_RESPONSE);
    }

    // ── 상점 설정 오류(사용자 결정: 승인 = 실패 확정, 취소 = 일시 오류)는 사람이 고쳐야 하므로 ERROR ──

    @Test
    void merchantConfigurationErrorIsLoggedAsError(CapturedOutput output) {
        TossStubs.expectConfirm(server).andRespond(TossErrorTableTest.error(401, "UNAUTHORIZED_KEY"));
        TossStubs.expectCancel(server).andRespond(TossErrorTableTest.error(401, "UNAUTHORIZED_KEY"));

        assertThat(client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey()))
                .isInstanceOf(TossCommandResult.Rejected.class);
        assertThat(client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey()))
                .isInstanceOf(TossCommandResult.Transient.class);
        assertThat(output.getAll().lines().filter(line -> line.contains("ERROR") && line.contains("상점 설정 오류")))
                .hasSize(2);
    }

    // ── 시크릿 · 결제 키가 새지 않는다 ──

    // 취소 · paymentKey 조회는 orderId 가 없다. 결제 키 끝 6자로 어느 결제인지 찾되 키 전체는 남기지 않는다.
    @Test
    void cancelAndPaymentKeyLookupAreTraceableByKeyTail(CapturedOutput output) {
        TossStubs.expectCancel(server).andRespond(TossErrorTableTest.error(400, "BRAND_NEW_CODE"));
        TossStubs.expectLookupByPaymentKey(server).andRespond(TossErrorTableTest.error(400, "BRAND_NEW_CODE"));

        client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey());
        client.findByPaymentKey(TossStubs.PAYMENT_REF);

        String tail = TossStubs.PAYMENT_REF.substring(TossStubs.PAYMENT_REF.length() - 6);
        assertThat(output.getAll().lines().filter(line -> line.contains("paymentKey=…" + tail))).hasSize(2);
        assertThat(output.getAll()).doesNotContain(TossStubs.PAYMENT_REF);
    }

    @Test
    void shortPaymentKeyIsNotPartiallyLogged() {
        assertThat(TossRequestRules.tail("short-key-1")).isEqualTo(TossRequestRules.MASKED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "connection", "unlisted", "html", "unreadable", "config", "mismatch",
            "status", "done"})
    void credentialAndPaymentKeyNeverLeak(String scenario, CapturedOutput output) {
        ResponseCreator response = switch (scenario) {
            case "timeout" -> withException(new SocketTimeoutException("Read timed out"));
            case "connection" -> withException(new ConnectException("Connection refused"));
            case "unlisted" -> TossErrorTableTest.error(400, "BRAND_NEW_CODE");
            case "html" -> withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html/>");
            case "unreadable" -> withSuccess("{", MediaType.APPLICATION_JSON);
            case "config" -> TossErrorTableTest.error(401, "UNAUTHORIZED_KEY");
            case "mismatch" -> withSuccess(TossStubs.payment(TossStubs.PAYMENT_REF, "another-order-0001", "DONE",
                    TossStubs.AMOUNT, TossStubs.AMOUNT), MediaType.APPLICATION_JSON);
            case "status" -> TossStubs.paymentResponse("WAITING_FOR_DEPOSIT");
            default -> TossStubs.paymentResponse("DONE");
        };
        expectAll(response);

        String results = String.join("\n",
                String.valueOf(client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey())),
                String.valueOf(client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey())),
                String.valueOf(client.findByPaymentKey(TossStubs.PAYMENT_REF)),
                String.valueOf(client.findByOrderId(TossStubs.ORDER_REF)));

        for (String leaked : new String[]{TossTestClients.MERCHANT_CREDENTIAL, BASIC.substring("Basic ".length()),
                TossStubs.PAYMENT_REF}) {
            assertThat(output.getAll()).doesNotContain(leaked);
            assertThat(results).doesNotContain(leaked);
        }
    }

    @Test
    void requestsAndPropertiesDoNotPrintSecrets() {
        assertThat(new TossProperties(TossTestClients.MERCHANT_CREDENTIAL).toString())
                .doesNotContain(TossTestClients.MERCHANT_CREDENTIAL);
        assertThat(TossStubs.confirmRequest().toString()).doesNotContain(TossStubs.PAYMENT_REF)
                .contains(TossStubs.ORDER_REF);
        assertThat(TossStubs.cancelRequest().toString()).doesNotContain(TossStubs.PAYMENT_REF);
    }

    private void expectAll(ResponseCreator response) {
        TossStubs.expectConfirm(server).andRespond(response);
        TossStubs.expectCancel(server).andRespond(response);
        TossStubs.expectLookupByPaymentKey(server).andRespond(response);
        TossStubs.expectLookupByOrderId(server).andRespond(response);
    }

    private void assertAllUnknown(UnknownReason reason) {
        assertThat(client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey()))
                .isEqualTo(new TossCommandResult.Unknown(reason, null));
        assertThat(client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey()))
                .isEqualTo(new TossCommandResult.Unknown(reason, null));
        assertThat(client.findByPaymentKey(TossStubs.PAYMENT_REF)).isEqualTo(new TossLookupResult.Unknown(reason, null));
        assertThat(client.findByOrderId(TossStubs.ORDER_REF)).isEqualTo(new TossLookupResult.Unknown(reason, null));
        server.verify();
    }
}
