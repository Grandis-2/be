package com.grandis.nova.payment.client.toss;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * 토스 개발자센터 "코어 API 별 에러"(2026-09-29 확인) 표 전체를 실제 HTTP 응답으로 흘려 분류를 본다.
 * 아래 표는 문서에서 그대로 옮긴 것이다 — {@link TossErrorCatalog} 를 복사하지 않는다. 둘이 어긋나면 여기서 드러난다.
 * 문서에 코드가 늘면 여기 행을 먼저 추가한다(추가 전에는 결과 불명 + WARN 으로 안전하게 떨어진다).
 *
 * 분류는 HTTP 상태가 아니라 코드로 한다(D8) — 상태 열은 문서 그대로 싣는 응답일 뿐이다(예: PROVIDER_ERROR 는 400 인데 일시 오류).
 */
@ExtendWith(OutputCaptureExtension.class)
class TossErrorTableTest {

    MockRestServiceServer server;
    TossPaymentClient client;

    @BeforeEach
    void setUp() {
        TossTestClients.Pair pair = TossTestClients.create();
        server = pair.server();
        client = pair.client();
    }

    // 결제 승인 POST /v1/payments/confirm — 문서 48행 + 멱등 키 오류 2행
    @ParameterizedTest(name = "승인 {1} {0} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            ALREADY_PROCESSED_PAYMENT                      | 400 | UNKNOWN
            ALREADY_PROCESSING_REQUEST                     | 400 | PROCESSING
            PROVIDER_ERROR                                 | 400 | TRANSIENT
            EXCEED_MAX_CARD_INSTALLMENT_PLAN               | 400 | REJECTED
            INVALID_REQUEST                                | 400 | REJECTED
            NOT_ALLOWED_POINT_USE                          | 400 | REJECTED
            INVALID_API_KEY                                | 400 | REJECTED
            INVALID_REJECT_CARD                            | 400 | REJECTED
            BELOW_MINIMUM_AMOUNT                           | 400 | REJECTED
            INVALID_CARD_EXPIRATION                        | 400 | REJECTED
            INVALID_STOPPED_CARD                           | 400 | REJECTED
            EXCEED_MAX_DAILY_PAYMENT_COUNT                 | 400 | REJECTED
            NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT | 400 | REJECTED
            INVALID_CARD_INSTALLMENT_PLAN                  | 400 | REJECTED
            NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN         | 400 | REJECTED
            EXCEED_MAX_PAYMENT_AMOUNT                      | 400 | REJECTED
            NOT_FOUND_TERMINAL_ID                          | 400 | REJECTED
            INVALID_AUTHORIZE_AUTH                         | 400 | REJECTED
            INVALID_CARD_LOST_OR_STOLEN                    | 400 | REJECTED
            RESTRICTED_TRANSFER_ACCOUNT                    | 400 | REJECTED
            INVALID_CARD_NUMBER                            | 400 | REJECTED
            INVALID_UNREGISTERED_SUBMALL                   | 400 | REJECTED
            NOT_REGISTERED_BUSINESS                        | 400 | REJECTED
            EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT             | 400 | REJECTED
            EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT            | 400 | REJECTED
            CARD_PROCESSING_ERROR                          | 400 | UNKNOWN
            EXCEED_MAX_AMOUNT                              | 400 | REJECTED
            INVALID_ACCOUNT_INFO_RE_REGISTER               | 400 | REJECTED
            NOT_AVAILABLE_PAYMENT                          | 400 | TRANSIENT
            UNAPPROVED_ORDER_ID                            | 400 | UNKNOWN
            EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT              | 400 | REJECTED
            UNAUTHORIZED_KEY                               | 401 | REJECTED
            REJECT_ACCOUNT_PAYMENT                         | 403 | REJECTED
            REJECT_CARD_PAYMENT                            | 403 | REJECTED
            REJECT_CARD_COMPANY                            | 403 | REJECTED
            FORBIDDEN_REQUEST                              | 403 | REJECTED
            REJECT_TOSSPAY_INVALID_ACCOUNT                 | 403 | REJECTED
            EXCEED_MAX_AUTH_COUNT                          | 403 | REJECTED
            EXCEED_MAX_ONE_DAY_AMOUNT                      | 403 | REJECTED
            NOT_AVAILABLE_BANK                             | 403 | TRANSIENT
            INVALID_PASSWORD                               | 403 | REJECTED
            INCORRECT_BASIC_AUTH_FORMAT                    | 403 | REJECTED
            FDS_ERROR                                      | 403 | REJECTED
            NOT_FOUND_PAYMENT                              | 404 | REJECTED
            NOT_FOUND_PAYMENT_SESSION                      | 404 | REJECTED
            FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING      | 500 | TRANSIENT
            FAILED_INTERNAL_SYSTEM_PROCESSING              | 500 | TRANSIENT
            UNKNOWN_PAYMENT_ERROR                          | 500 | UNKNOWN
            INVALID_IDEMPOTENCY_KEY                        | 400 | REJECTED
            IDEMPOTENT_REQUEST_PROCESSING                  | 409 | PROCESSING
            """)
    void confirmErrors(String code, int status, Expected expected, CapturedOutput output) {
        TossStubs.expectConfirm(server).andRespond(error(status, code));

        TossCommandResult result = client.confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

        expected.assertCommand(result, code);
        assertThat(output.getAll()).doesNotContain("표에 없는 코드");
        server.verify();
    }

    // 결제 취소 POST /v1/payments/{paymentKey}/cancel — 문서 30행 + 멱등 키 오류 2행
    @ParameterizedTest(name = "취소 {1} {0} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            ALREADY_CANCELED_PAYMENT                       | 400 | UNKNOWN
            INVALID_REFUND_ACCOUNT_INFO                    | 400 | REJECTED
            EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT           | 400 | REJECTED
            INVALID_REQUEST                                | 400 | REJECTED
            INVALID_REFUND_ACCOUNT_NUMBER                  | 400 | REJECTED
            INVALID_BANK                                   | 400 | REJECTED
            NOT_MATCHES_REFUNDABLE_AMOUNT                  | 400 | UNKNOWN
            PROVIDER_ERROR                                 | 400 | TRANSIENT
            REFUND_REJECTED                                | 400 | REJECTED
            ALREADY_REFUND_PAYMENT                         | 400 | UNKNOWN
            FORBIDDEN_BANK_REFUND_REQUEST                  | 400 | REJECTED
            UNAUTHORIZED_KEY                               | 401 | TRANSIENT
            NOT_CANCELABLE_AMOUNT                          | 403 | REJECTED
            FORBIDDEN_CONSECUTIVE_REQUEST                  | 403 | TRANSIENT
            FORBIDDEN_REQUEST                              | 403 | TRANSIENT
            NOT_CANCELABLE_PAYMENT                         | 403 | REJECTED
            EXCEED_MAX_REFUND_DUE                          | 403 | REJECTED
            NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT     | 403 | REJECTED
            NOT_ALLOWED_PARTIAL_REFUND                     | 403 | REJECTED
            NOT_AVAILABLE_BANK                             | 403 | TRANSIENT
            INCORRECT_BASIC_AUTH_FORMAT                    | 403 | TRANSIENT
            NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER        | 403 | REJECTED
            EXCEED_CANCEL_LIMIT                            | 403 | REJECTED
            NOT_FOUND_PAYMENT                              | 404 | REJECTED
            FAILED_INTERNAL_SYSTEM_PROCESSING              | 500 | TRANSIENT
            FAILED_REFUND_PROCESS                          | 500 | TRANSIENT
            FAILED_METHOD_HANDLING_CANCEL                  | 500 | TRANSIENT
            FAILED_PARTIAL_REFUND                          | 500 | UNKNOWN
            COMMON_ERROR                                   | 500 | TRANSIENT
            FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING      | 500 | TRANSIENT
            INVALID_IDEMPOTENCY_KEY                        | 400 | REJECTED
            IDEMPOTENT_REQUEST_PROCESSING                  | 409 | PROCESSING
            """)
    void cancelErrors(String code, int status, Expected expected, CapturedOutput output) {
        TossStubs.expectCancel(server).andRespond(error(status, code));

        TossCommandResult result = client.cancel(TossStubs.cancelRequest(), TossStubs.idempotencyKey());

        expected.assertCommand(result, code);
        assertThat(output.getAll()).doesNotContain("표에 없는 코드");
        server.verify();
    }

    // 결제 조회 GET /v1/payments/{paymentKey} · /v1/payments/orders/{orderId} — 문서 7행
    @ParameterizedTest(name = "조회 {1} {0} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN_BELOW_AMOUNT | 400 | UNKNOWN
            UNAUTHORIZED_KEY                               | 401 | UNKNOWN
            FORBIDDEN_CONSECUTIVE_REQUEST                  | 403 | UNKNOWN
            INCORRECT_BASIC_AUTH_FORMAT                    | 403 | UNKNOWN
            NOT_FOUND_PAYMENT                              | 404 | NOT_FOUND
            NOT_FOUND                                      | 404 | NOT_FOUND
            FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING      | 500 | UNKNOWN
            """)
    void lookupErrors(String code, int status, Expected expected, CapturedOutput output) {
        TossStubs.expectLookupByPaymentKey(server).andRespond(error(status, code));
        TossStubs.expectLookupByOrderId(server).andRespond(error(status, code));

        expected.assertLookup(client.findByPaymentKey(TossStubs.PAYMENT_REF), code);
        expected.assertLookup(client.findByOrderId(TossStubs.ORDER_REF), code);
        assertThat(output.getAll()).doesNotContain("표에 없는 코드");
        server.verify();
    }

    static org.springframework.test.web.client.ResponseCreator error(int status, String code) {
        return withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"%s\",\"message\":\"문서 메시지\"}".formatted(code));
    }

    enum Expected {
        REJECTED, TRANSIENT, PROCESSING, UNKNOWN, NOT_FOUND;

        void assertCommand(TossCommandResult result, String code) {
            switch (this) {
                case REJECTED -> assertThat(result).isEqualTo(new TossCommandResult.Rejected(code, "문서 메시지"));
                case TRANSIENT -> assertThat(result).isEqualTo(new TossCommandResult.Transient(code, "문서 메시지"));
                case PROCESSING -> assertThat(result).isEqualTo(new TossCommandResult.Processing(code));
                case UNKNOWN -> assertThat(result)
                        .isEqualTo(new TossCommandResult.Unknown(UnknownReason.LISTED_CODE, code));
                case NOT_FOUND -> throw new IllegalStateException("승인 · 취소에는 없음 분류가 없다");
            }
        }

        void assertLookup(TossLookupResult result, String code) {
            switch (this) {
                case NOT_FOUND -> assertThat(result).isEqualTo(new TossLookupResult.NotFound(code));
                case UNKNOWN -> assertThat(result)
                        .isEqualTo(new TossLookupResult.Unknown(UnknownReason.LISTED_CODE, code));
                default -> throw new IllegalStateException("조회는 찾음 · 없음 · 알 수 없음뿐이다");
            }
        }
    }
}
