package com.grandis.nova.order.order.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.PaymentDeadLetter;
import com.grandis.nova.order.client.payment.PaymentDeadLetterBatch;
import com.grandis.nova.order.client.payment.PaymentDeadLetterBatchRequest;
import com.grandis.nova.order.client.payment.PaymentDeadLetterClient;
import com.grandis.nova.order.client.payment.PaymentDeadLetterDiscardRequest;
import com.grandis.nova.order.client.payment.PaymentDeadLetterFailureReason;
import com.grandis.nova.order.client.payment.PaymentDeadLetterStatus;
import com.grandis.nova.order.client.payment.PaymentDeadLetterSummary;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/v1/admin/orders/payment-dead-letters — payment 관리자 DLQ API 를 그대로 넘긴다. payment 내부 API 만 대역이다
 * (응답 모양의 계약 대조는 PaymentDeadLettersTest 가 한다). 인증은 흉내({@link TestAuth})라 Authorization 헤더 값은 전달 확인용 자리다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class AdminPaymentDeadLetterApiTest {

    static final String BASE = "/api/v1/admin/orders/payment-dead-letters";
    // 관리자가 보낸 액세스 토큰 자리. payment 에 그대로 전달돼야 한다. 실제 토큰 모양이 아니다.
    static final String SESSION = "admin-payment-dead-letter-api-test";
    static final String AUTHORIZATION = BearerTokens.value(SESSION);
    static final UUID ID = UUID.fromString("00000000-0000-7000-8000-0000000000d2");
    static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a2");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    PaymentDeadLetterClient client;

    // 고정 경로가 주문 상세(/{orderId})에 먹히지 않는다 — 주문 조회로 갔다면 주문 없음(404)이고 payment 를 부르지 않는다
    @Test
    void 목록은_주문_상세_경로와_겹치지_않고_조건과_토큰을_그대로_넘긴다() throws Exception {
        given(client.list(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .willReturn(ApiResponse.ok(OffsetPage.of(List.of(summary()), 0, 20, 1)));

        asAdmin(get(BASE).param("status", "OPEN").param("failureReason", "PROCESSING_FAILED")
                .param("orderId", ORDER_ID.toString()).param("from", "2026-10-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].deadLetterId").value(ID.toString()))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.data.total").value(1));

        verify(client).list(eq(PaymentDeadLetterStatus.OPEN), isNull(), eq(PaymentDeadLetterFailureReason.PROCESSING_FAILED),
                eq(ORDER_ID), eq(Instant.parse("2026-10-01T00:00:00Z")), isNull(), eq(0), eq(20), eq(AUTHORIZATION));
        // 주문 상세는 그대로 주문 조회로 간다
        asAdmin(get("/api/v1/admin/orders/{orderId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void 상세_되돌리기_일괄_버리기를_같은_경로_상태로_넘긴다() throws Exception {
        given(client.get(ID, AUTHORIZATION)).willReturn(ApiResponse.ok(detail()));
        given(client.redrive(ID, AUTHORIZATION)).willReturn(ApiResponse.ok(summary()));
        given(client.redriveBatch(new PaymentDeadLetterBatchRequest(List.of(ID), null, null, 50), AUTHORIZATION))
                .willReturn(ApiResponse.ok(new PaymentDeadLetterBatch(1, 0, 1)));
        given(client.discard(ID, new PaymentDeadLetterDiscardRequest("재발행 요청"), AUTHORIZATION))
                .willReturn(ApiResponse.ok(detail()));

        asAdmin(get(BASE + "/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceQueue").value("payment-events"))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()));
        asAdmin(post(BASE + "/{id}/redrive", ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.deadLetterId").value(ID.toString()));
        asAdmin(post(BASE + "/redrive-batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deadLetterIds\":[\"%s\"],\"ratePerSecond\":50}".formatted(ID)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.targetCount").value(1));
        asAdmin(post(BASE + "/{id}/discard", ID).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"재발행 요청\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deadLetterId").value(ID.toString()));
    }

    @Test
    void 관리자만_쓴다() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE).with(TestAuth.customer(UUID.randomUUID()))).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/{id}/redrive", ID).with(TestAuth.customer(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(client);
    }

    @Test
    void payment_의_DLQ_업무_오류는_같은_코드와_상태로_돌려준다() throws Exception {
        given(client.get(ID, AUTHORIZATION)).willThrow(rejection(HttpStatus.NOT_FOUND, "DEAD_LETTER_NOT_FOUND", "null"));
        given(client.redrive(ID, AUTHORIZATION))
                .willThrow(rejection(HttpStatus.CONFLICT, "DEAD_LETTER_NOT_REDRIVABLE", "{\"reason\":\"status=REDRIVEN\"}"));
        given(client.discard(ID, new PaymentDeadLetterDiscardRequest("x"), AUTHORIZATION))
                .willThrow(rejection(HttpStatus.CONFLICT, "DEAD_LETTER_NOT_DISCARDABLE", "{\"reason\":\"status=DISCARDED\"}"));

        asAdmin(get(BASE + "/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_FOUND"));
        asAdmin(post(BASE + "/{id}/redrive", ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_REDRIVABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("status=REDRIVEN"));
        asAdmin(post(BASE + "/{id}/discard", ID).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEAD_LETTER_NOT_DISCARDABLE"));
    }

    @Test
    void payment_가_응답하지_않으면_503_이다() throws Exception {
        given(client.redrive(ID, AUTHORIZATION)).willThrow(new ResourceAccessException("Read timed out"));
        given(client.get(ID, AUTHORIZATION))
                .willThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        asAdmin(post(BASE + "/{id}/redrive", ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
        asAdmin(get(BASE + "/{id}", ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void 요청_값은_payment_에_넘기기_전에_가른다() throws Exception {
        asAdmin(get(BASE).param("size", "101")).andExpect(status().isBadRequest());
        asAdmin(get(BASE).param("status", "NOPE")).andExpect(status().isBadRequest());
        asAdmin(get(BASE + "/{id}", "not-a-uuid")).andExpect(status().isBadRequest());
        asAdmin(post(BASE + "/redrive-batch").contentType(MediaType.APPLICATION_JSON).content("{\"ratePerSecond\":201}"))
                .andExpect(status().isBadRequest());
        asAdmin(post(BASE + "/{id}/discard", ID).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\" \"}"))
                .andExpect(status().isBadRequest());
        asAdmin(post(BASE + "/{id}/discard", ID).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"%s\"}".formatted("x".repeat(501))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(client);
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(TestAuth.admin()).header(BearerTokens.HEADER, AUTHORIZATION));
    }

    private HttpClientErrorException rejection(HttpStatus status, String code, String details) {
        String body = """
                {"success":false,"data":null,"error":{"code":"%s","message":"m","details":%s}}
                """.formatted(code, details);
        HttpClientErrorException rejected = HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        // RestClient 가 붙이는 본문 변환을 흉내 낸다(직접 만든 예외에는 없다)
        rejected.setBodyConvertFunction(type -> jsonMapper.readValue(body, jsonMapper.constructType(type.getType())));
        return rejected;
    }

    private static PaymentDeadLetterSummary summary() {
        Instant at = Instant.parse("2026-10-04T01:10:00Z");
        return new PaymentDeadLetterSummary(ID, "m-1", "e-1", "ORDER_REFUND_REQUESTED", ORDER_ID,
                "PROCESSING_FAILED", 5, "OPEN", true, null, null, at, at);
    }

    private static PaymentDeadLetter detail() {
        Instant at = Instant.parse("2026-10-04T01:10:00Z");
        return new PaymentDeadLetter(ID, "payment-events", "m-1", "e-1", "ORDER_REFUND_REQUESTED", "ORDER", ORDER_ID,
                ORDER_ID, "PROCESSING_FAILED", 5, "OPEN", true, null,
                "{}", null, null, null, null, null, null, null, null, at, at);
    }
}
