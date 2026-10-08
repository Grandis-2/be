package com.grandis.nova.order.client.preorder;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.security.BearerTokens;
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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 실제 계약(order-handoff.md 요청 2, preorder InternalPreorderController)의 경로 · 헤더 · 응답을 그대로 읽는지.
 * 헤더는 common:security 인증 필터가 읽는 Authorization: Bearer 다(NV-137 — 이전 X-Session-Token 은 더 이상 읽히지 않는다).
 *
 * 계약 대조: 아래 JSON 은 preorder PayabilityResponse 를 ApiResponse 로 감싼 모양을 복제한 것이다(preorder 는 order 의
 * 의존성이 아니라 직접 직렬화할 수 없다). preorder 쪽 필드가 바뀌면 이 예시도 같이 바꾼다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PreorderClientTest {

    static final String PREORDER_UUID = "0b8f6a3e-5a8c-4d59-9a53-3c1f0e0f7a11";
    static final String PAYABILITY_URL = "http://preorder/internal/preorders/" + PREORDER_UUID + "/payability";
    // 전달할 액세스 토큰 자리(접두어 없음). 로그 검사에도 쓴다. 실제 토큰 모양이 아니다.
    static final String SESSION = "order-client-test-user-7";

    MockRestServiceServer server;
    PreorderClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://preorder");
        server = MockRestServiceServer.bindTo(builder).build();
        client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(PreorderClient.class);
    }

    /*
     * 보내는 헤더가 받는 쪽 필터의 규칙(BearerTokens.extract)으로 다시 읽히는지. 이름 · 접두어를 값으로 박지 않고
     * 같은 규칙으로 대조한다 — 규칙이 바뀌면 여기서 드러난다. 옛 헤더는 싣지 않는다.
     */
    @Test
    void forwardedHeaderIsReadableByAuthenticationFilter() {
        server.expect(requestTo(PAYABILITY_URL))
                .andExpect(request -> assertThat(BearerTokens.parse(request.getHeaders().getFirst(BearerTokens.HEADER)))
                        .contains(SESSION))
                .andExpect(headerDoesNotExist("X-Session-Token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        catchThrowable(() -> new PreorderReader(client).find(PREORDER_UUID, SESSION));
        server.verify();
    }

    @Test
    void readsPayablePreorderFromEnvelopeAndForwardsSessionToken() {
        server.expect(requestTo(PAYABILITY_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(BearerTokens.HEADER, BearerTokens.value(SESSION)))
                .andRespond(withSuccess("""
                        {"success":true,
                         "data":{"preorderId":"%s","preorderInternalId":"00000000-0000-7000-8000-000000050231",
                                 "customerId":"00000000-0000-7000-8000-000000001024",
                                 "productId":"00000000-0000-7000-8000-000000000101",
                                 "optionId":"00000000-0000-7000-8000-000000001002","productTitle":"갤럭시 G999","optionTitle":"256GB 블랙",
                                 "unitPrice":1290000,"status":"PAYABLE",
                                 "payableFrom":"2026-09-03T01:00:03.470Z","paymentDueAt":"2026-09-04T01:00:03.470Z",
                                 "payable":true,"reason":null},
                         "error":null,"timestamp":"2026-09-03T02:00:00Z","traceId":"t-1"}
                        """.formatted(PREORDER_UUID), MediaType.APPLICATION_JSON));

        PreorderPayability payability = client.getPayability(PREORDER_UUID, BearerTokens.value(SESSION)).data();

        assertThat(payability).isEqualTo(new PreorderPayability(PREORDER_UUID, TestIds.id(50231), TestIds.id(1024),
                TestIds.id(101), TestIds.id(1002),
                "갤럭시 G999", "256GB 블랙", new BigDecimal("1290000"), "PAYABLE",
                Instant.parse("2026-09-03T01:00:03.470Z"), Instant.parse("2026-09-04T01:00:03.470Z"), true, null));
        server.verify();
    }

    // 결제 불가 예시. 등록 전이면 payableFrom · paymentDueAt 이 null 이다(preorder Preorder.paymentDueAt()).
    @Test
    void readsBlockedPreorder() {
        server.expect(requestTo(PAYABILITY_URL))
                .andRespond(withSuccess("""
                        {"success":true,
                         "data":{"preorderId":"%s","preorderInternalId":"00000000-0000-7000-8000-000000050231",
                                 "customerId":"00000000-0000-7000-8000-000000001024",
                                 "productId":"00000000-0000-7000-8000-000000000101",
                                 "optionId":"00000000-0000-7000-8000-000000001002","productTitle":"갤럭시 G999","optionTitle":"256GB 블랙",
                                 "unitPrice":1290000,"status":"PENDING_SYNC",
                                 "payableFrom":null,"paymentDueAt":null,
                                 "payable":false,"reason":"NOT_YET_REGISTERED"},
                         "error":null,"timestamp":"2026-09-03T02:00:00Z","traceId":"t-1"}
                        """.formatted(PREORDER_UUID), MediaType.APPLICATION_JSON));

        PreorderPayability payability = client.getPayability(PREORDER_UUID, BearerTokens.value(SESSION)).data();

        assertThat(payability.payable()).isFalse();
        assertThat(payability.reason()).isEqualTo("NOT_YET_REGISTERED");
        assertThat(payability.payableFrom()).isNull();
        assertThat(payability.paymentDueAt()).isNull();
    }

    // 헤더가 없으면 빈 값으로라도 싣지 않는다 — preorder 가 401 로 판단한다.
    @Test
    void missingSessionTokenIsNotSent() {
        server.expect(requestTo(PAYABILITY_URL))
                .andExpect(headerDoesNotExist(BearerTokens.HEADER))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getPayability(PREORDER_UUID, null))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        server.verify();
    }

    @Test
    void missingPreorderIsNotFound() {
        server.expect(requestTo(PAYABILITY_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getPayability(PREORDER_UUID, BearerTokens.value(SESSION)))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    // 404 는 본문의 code 로 나뉜다: 예약 없음(PREORDER_NOT_FOUND)만 빈 결과, 경로 없음(공통 NOT_FOUND) · 본문 없음은 연동 오류.
    @Test
    void preorderNotFoundBodyIsEmpty() {
        server.expect(requestTo(PAYABILITY_URL)).andRespond(notFound("PREORDER_NOT_FOUND"));

        assertThat(new PreorderReader(client).find(PREORDER_UUID, SESSION)).isEmpty();
    }

    @Test
    void routeNotFoundBodyIsIntegrationError() {
        server.expect(requestTo(PAYABILITY_URL)).andRespond(notFound("NOT_FOUND"));

        assertThatThrownBy(() -> new PreorderReader(client).find(PREORDER_UUID, SESSION))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void notFoundWithoutBodyIsIntegrationError() {
        server.expect(requestTo(PAYABILITY_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> new PreorderReader(client).find(PREORDER_UUID, SESSION))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void someoneElsesPreorderIsForbidden() {
        server.expect(requestTo(PAYABILITY_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getPayability(PREORDER_UUID, BearerTokens.value(SESSION)))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    /*
     * 2xx 인데 응답이 계약과 다르면(본문 모양 · Content-Type) 다시 불러도 같다 — 일시 장애(503)가 아니라 연동 오류(500).
     * RestClient 가 실제로 올리는 예외 모양(원인 HttpMessageNotReadableException / UnknownContentTypeException)을 여기서 본다.
     */
    @Test
    void malformedBodyIsIntegrationError(CapturedOutput output) {
        server.expect(requestTo(PAYABILITY_URL))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"payable\":\"not-a-boolean\"", MediaType.APPLICATION_JSON));

        Throwable thrown = catchThrowable(() -> new PreorderReader(client).find(PREORDER_UUID, SESSION));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertNoCredential(thrown, output);
    }

    @Test
    void unexpectedContentTypeIsIntegrationError(CapturedOutput output) {
        server.expect(requestTo(PAYABILITY_URL)).andRespond(withSuccess("<html>gateway</html>", MediaType.TEXT_HTML));

        Throwable thrown = catchThrowable(() -> new PreorderReader(client).find(PREORDER_UUID, SESSION));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertNoCredential(thrown, output);
    }

    // 실패를 로그로 남기는 경로(연동 오류 · 장애)에서도 전달한 토큰은 로그와 예외 메시지에 없다.
    @ParameterizedTest
    @EnumSource(value = HttpStatus.class,
            names = {"BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "SERVICE_UNAVAILABLE"})
    void sessionTokenIsNotLoggedOnFailure(HttpStatus status, CapturedOutput output) {
        server.expect(requestTo(PAYABILITY_URL)).andRespond(withStatus(status).body("{\"success\":false}")
                .contentType(MediaType.APPLICATION_JSON));

        Throwable thrown = catchThrowable(() -> new PreorderReader(client).find(PREORDER_UUID, SESSION));

        assertNoCredential(thrown, output);
    }

    @Test
    void sessionTokenIsNotLoggedOnTimeout(CapturedOutput output) {
        server.expect(requestTo(PAYABILITY_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        Throwable thrown = catchThrowable(() -> new PreorderReader(client).find(PREORDER_UUID, SESSION));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertNoCredential(thrown, output);
    }

    private static void assertNoCredential(Throwable thrown, CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(SESSION);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(SESSION);
        }
    }

    private static DefaultResponseCreator notFound(String code) {
        return withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                {"success":false,"data":null,"error":{"code":"%s","message":"없음","details":null},
                 "timestamp":"2026-09-03T02:00:00Z","traceId":"t-1"}
                """.formatted(code));
    }
}
