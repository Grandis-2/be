package com.grandis.nova.payment.client.toss;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ResponseCreator;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 토스 요청 · 응답 예시. 값은 문서 형식을 따른 가짜다. */
final class TossStubs {

    // 토스 paymentKey 자리(결제 식별자). 로그에 남지 않아야 하는 값이라 검사에도 쓴다
    static final String PAYMENT_REF = "tviva20260929nv98payment0001";
    // 우리가 만든 토스 orderId(시도마다 UUID, D11)
    static final String ORDER_REF = "7d3e2c1a-0b9f-4e8d-a6c5-3f2e1d0c9b8a";
    static final long AMOUNT = 1_290_000L;
    static final String DEDUP = "5f0c9a52-8a3e-4c1b-9d7e-2b6a4f8e1c30";
    static final String CANCEL_REASON = "구매자 예약 취소";

    static final String CONFIRM_URL = TossTestClients.BASE_URL + "/v1/payments/confirm";
    static final String CANCEL_URL = TossTestClients.BASE_URL + "/v1/payments/" + PAYMENT_REF + "/cancel";
    static final String LOOKUP_BY_PAYMENT_URL = TossTestClients.BASE_URL + "/v1/payments/" + PAYMENT_REF;
    static final String LOOKUP_BY_ORDER_URL = TossTestClients.BASE_URL + "/v1/payments/orders/" + ORDER_REF;

    private TossStubs() {
    }

    static TossConfirmRequest confirmRequest() {
        return new TossConfirmRequest(PAYMENT_REF, ORDER_REF, AMOUNT);
    }

    static TossCancelRequest cancelRequest() {
        return new TossCancelRequest(PAYMENT_REF, CANCEL_REASON);
    }

    static TossIdempotencyKey idempotencyKey() {
        return new TossIdempotencyKey(DEDUP);
    }

    static ResponseActions expectConfirm(MockRestServiceServer server) {
        return server.expect(requestTo(CONFIRM_URL)).andExpect(method(HttpMethod.POST));
    }

    static ResponseActions expectCancel(MockRestServiceServer server) {
        return server.expect(requestTo(CANCEL_URL)).andExpect(method(HttpMethod.POST));
    }

    static ResponseActions expectLookupByPaymentKey(MockRestServiceServer server) {
        return server.expect(requestTo(LOOKUP_BY_PAYMENT_URL)).andExpect(method(HttpMethod.GET));
    }

    static ResponseActions expectLookupByOrderId(MockRestServiceServer server) {
        return server.expect(requestTo(LOOKUP_BY_ORDER_URL)).andExpect(method(HttpMethod.GET));
    }

    /** Payment 객체(문서 예시에서 이 클라이언트가 읽는 필드 + 읽지 않는 필드 몇 개). */
    static String payment(String paymentKey, String orderId, String status, long totalAmount, long balanceAmount) {
        return """
                {"mId":"tosspayments","lastTransactionKey":"txn-approve-0001",
                 "paymentKey":"%s","orderId":"%s","orderName":"갤럭시 G999 256GB 블랙",
                 "taxExemptionAmount":0,"status":"%s","requestedAt":"2026-09-29T15:30:04+09:00",
                 "approvedAt":"2026-09-29T15:31:12+09:00","useEscrow":false,"cultureExpense":false,
                 "card":{"issuerCode":"71","acquirerCode":"71","number":"12345678****000*","installmentPlanMonths":0},
                 "type":"NORMAL","currency":"KRW","totalAmount":%d,"balanceAmount":%d,
                 "suppliedAmount":1172727,"vat":117273,"taxFreeAmount":0,"method":"카드","version":"2022-11-16"}
                """.formatted(paymentKey, orderId, status, totalAmount, balanceAmount);
    }

    /** 전액 취소된 Payment 객체(취소 내역 한 건, 문서 예시 모양). */
    static String canceledPayment(String paymentKey, String orderId, long amount) {
        String payment = payment(paymentKey, orderId, "CANCELED", amount, 0);
        return payment.substring(0, payment.lastIndexOf('}')) + """
                ,"cancels":[{"transactionKey":"txn-cancel-0001","cancelReason":"%s","taxExemptionAmount":0,
                 "canceledAt":"2026-10-04T10:20:30+09:00","transferDiscountAmount":0,"easyPayDiscountAmount":0,
                 "receiptKey":null,"cancelAmount":%d,"taxFreeAmount":0,"refundableAmount":0,"cancelStatus":"DONE",
                 "cancelRequestId":null}]}
                """.formatted(CANCEL_REASON, amount);
    }

    static ResponseCreator paymentResponse(String status) {
        String body = status.equals("CANCELED")
                ? canceledPayment(PAYMENT_REF, ORDER_REF, AMOUNT)
                : payment(PAYMENT_REF, ORDER_REF, status, AMOUNT, AMOUNT);
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }
}
