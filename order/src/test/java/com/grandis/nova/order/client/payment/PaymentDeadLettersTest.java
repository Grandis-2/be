package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.order.OrderErrorCode;
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
 * 실제 계약(payment InternalDeadLetterController)의 경로 · 헤더 · 쿼리 · 본문 · 응답을 그대로 쓰고 읽는지, 실패를 어떻게 가르는지.
 *
 * 계약 대조: 아래 JSON 은 payment DeadLetterSummaryResponse · DeadLetterResponse · BatchRedrive · BatchRedriveProgress(ApiResponse
 * 봉투)를 복제한 것이다.
 * payment 쪽 필드가 바뀌면 이 예시도 같이 바꾼다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentDeadLettersTest {

    static final String BASE_URL = "http://payment/internal/dead-letters";
    static final UUID ID = UUID.fromString("00000000-0000-7000-8000-0000000000d1");
    static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    // 전달할 관리자 액세스 토큰 자리(접두어 없음). 로그 검사에도 쓴다. 실제 토큰 모양이 아니다.
    static final String SESSION = "payment-dead-letter-test-admin";

    MockRestServiceServer server;
    PaymentDeadLetters deadLetters;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://payment");
        server = MockRestServiceServer.bindTo(builder).build();
        PaymentDeadLetterClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build().createClient(PaymentDeadLetterClient.class);
        deadLetters = new PaymentDeadLetters(client);
    }

    @Test
    void 목록은_거르는_조건과_관리자_토큰을_그대로_싣고_페이지를_읽는다() {
        server.expect(requestTo(BASE_URL + "?status=OPEN&failureReason=PROCESSING_FAILED&orderId=" + ORDER_ID
                        + "&from=2026-10-01T00%3A00%3A00Z&page=1&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(forwardedToken())
                .andRespond(ok("""
                        {"items":[%s],"page":1,"size":20,"total":21}""".formatted(SUMMARY)));

        OffsetPage<PaymentDeadLetterSummary> page = deadLetters.list(PaymentDeadLetterStatus.OPEN, null,
                PaymentDeadLetterFailureReason.PROCESSING_FAILED, ORDER_ID, Instant.parse("2026-10-01T00:00:00Z"), null,
                1, 20, SESSION);

        assertThat(page.total()).isEqualTo(21);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.deadLetterId()).isEqualTo(ID);
            assertThat(item.orderId()).isEqualTo(ORDER_ID);
            assertThat(item.status()).isEqualTo("OPEN");
            assertThat(item.redrivable()).isTrue();
        });
        server.verify();
    }

    @Test
    void 상세_되돌리기_일괄_버리기는_계약의_경로와_본문으로_부른다() {
        server.expect(requestTo(BASE_URL + "/" + ID)).andExpect(method(HttpMethod.GET)).andExpect(forwardedToken())
                .andRespond(ok(DETAIL));
        server.expect(requestTo(BASE_URL + "/" + ID + "/redrive")).andExpect(method(HttpMethod.POST))
                .andExpect(forwardedToken())
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(envelope(SUMMARY)));
        server.expect(requestTo(BASE_URL + "/redrive-batch")).andExpect(method(HttpMethod.POST)).andExpect(forwardedToken())
                .andExpect(content().json("""
                        {"deadLetterIds":["%s"],"eventType":null,"failureReason":null,"ratePerSecond":50}
                        """.formatted(ID), JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body(envelope("{\"batchId\":\"%s\",\"targetCount\":1,\"skippedCount\":0,\"estimatedSeconds\":1}"
                                .formatted(BATCH_ID))));
        server.expect(requestTo(BASE_URL + "/redrive-batches/" + BATCH_ID)).andExpect(method(HttpMethod.GET))
                .andExpect(forwardedToken())
                .andRespond(ok(PROGRESS));
        server.expect(requestTo(BASE_URL + "/" + ID + "/discard")).andExpect(method(HttpMethod.POST))
                .andExpect(forwardedToken())
                .andExpect(content().json("{\"note\":\"재발행 요청\"}", JsonCompareMode.STRICT))
                .andRespond(ok(DETAIL));

        assertThat(deadLetters.get(ID, SESSION).body()).isEqualTo("{}");
        assertThat(deadLetters.redrive(ID, SESSION).deadLetterId()).isEqualTo(ID);
        assertThat(deadLetters.redriveBatch(new PaymentDeadLetterBatchRequest(List.of(ID), null, null, 50), SESSION))
                .isEqualTo(new PaymentDeadLetterBatch(BATCH_ID, 1, 0, 1));
        assertThat(deadLetters.redriveBatchProgress(BATCH_ID, SESSION)).isEqualTo(new PaymentDeadLetterBatchProgress(
                BATCH_ID, 3, 1, 2, 1, 1, 0, 0, 1, Instant.parse("2026-10-04T01:00:00Z"),
                Instant.parse("2026-10-04T01:00:01Z"), null));
        assertThat(deadLetters.discard(ID, new PaymentDeadLetterDiscardRequest("재발행 요청"), SESSION).sourceQueue())
                .isEqualTo("payment-events");
        server.verify();
    }

    // payment 의 DLQ 업무 오류는 관리자가 가를 수 있게 같은 코드 · 상태 · details 로 넘긴다
    @Test
    void DLQ_업무_오류는_같은_코드와_details_로_넘긴다() {
        server.expect(requestTo(BASE_URL + "/" + ID)).andRespond(rejection(HttpStatus.NOT_FOUND, "DEAD_LETTER_NOT_FOUND", "null"));
        server.expect(requestTo(BASE_URL + "/" + ID + "/redrive"))
                .andRespond(rejection(HttpStatus.CONFLICT, "DEAD_LETTER_NOT_REDRIVABLE", "{\"reason\":\"status=REDRIVEN\"}"));
        server.expect(requestTo(BASE_URL + "/" + ID + "/discard"))
                .andRespond(rejection(HttpStatus.CONFLICT, "DEAD_LETTER_NOT_DISCARDABLE", "{\"reason\":\"status=DISCARDED\"}"));
        server.expect(requestTo(BASE_URL + "/redrive-batches/" + BATCH_ID))
                .andRespond(rejection(HttpStatus.NOT_FOUND, "REDRIVE_BATCH_NOT_FOUND", "null"));

        assertThat(catchThrowable(() -> deadLetters.get(ID, SESSION))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.DEAD_LETTER_NOT_FOUND));
        assertThat(catchThrowable(() -> deadLetters.redrive(ID, SESSION))).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(OrderErrorCode.DEAD_LETTER_NOT_REDRIVABLE);
            assertThat(e.details()).isEqualTo(Map.of("reason", "status=REDRIVEN"));
        });
        assertThat(catchThrowable(() -> deadLetters.discard(ID, new PaymentDeadLetterDiscardRequest("x"), SESSION))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.DEAD_LETTER_NOT_DISCARDABLE));
        assertThat(catchThrowable(() -> deadLetters.redriveBatchProgress(BATCH_ID, SESSION)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.REDRIVE_BATCH_NOT_FOUND));
    }

    // order 의 필터가 방금 통과시킨 토큰이다 — payment 의 거절은 payment 쪽 사정으로 보고 다시 시도를 안내한다(PaymentPreparer 와 같다)
    @Test
    void 전달_토큰_거절은_503_이다(CapturedOutput output) {
        server.expect(requestTo(BASE_URL + "/" + ID)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertUnavailable(catchThrowable(() -> deadLetters.get(ID, SESSION)));
        assertThat(output.getAll()).contains("전달 토큰 거절(401)").doesNotContain(SESSION);
    }

    // 코드가 다르거나 본문이 없는 4xx 는 order 가 계약을 어긴 것이다 — 다시 불러도 같으므로 연동 오류(500)
    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "FORBIDDEN", "NOT_FOUND", "CONFLICT"})
    void 그_밖의_4xx_는_연동_오류다(HttpStatus status) {
        server.expect(requestTo(BASE_URL + "/" + ID)).andRespond(withStatus(status));

        assertThat(catchThrowable(() -> deadLetters.get(ID, SESSION))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 상태와_코드가_맞지_않으면_넘기지_않는다() {
        server.expect(requestTo(BASE_URL + "/" + ID)).andRespond(rejection(HttpStatus.CONFLICT, "DEAD_LETTER_NOT_FOUND", "null"));

        assertThat(catchThrowable(() -> deadLetters.get(ID, SESSION))).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"INTERNAL_SERVER_ERROR", "SERVICE_UNAVAILABLE"})
    void 서버_오류는_503_이다(HttpStatus status) {
        server.expect(requestTo(BASE_URL + "/" + ID + "/redrive")).andRespond(withStatus(status));

        assertUnavailable(catchThrowable(() -> deadLetters.redrive(ID, SESSION)));
    }

    @Test
    void 응답이_없으면_503_이고_토큰은_로그에_없다(CapturedOutput output) {
        server.expect(requestTo(BASE_URL + "/" + ID + "/redrive"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertUnavailable(catchThrowable(() -> deadLetters.redrive(ID, SESSION)));
        assertThat(output.getAll()).doesNotContain(SESSION);
    }

    // payment 가 상태 · 분류 값을 더해도 중계는 읽기 실패로 막히지 않는다
    @Test
    void 모르는_상태_분류_값도_그대로_읽는다() {
        server.expect(requestTo(BASE_URL + "/" + ID + "/redrive")).andRespond(withStatus(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON).body(envelope(SUMMARY.replace("\"OPEN\"", "\"ARCHIVED\"")
                        .replace("\"PROCESSING_FAILED\"", "\"SOMETHING_NEW\""))));

        PaymentDeadLetterSummary summary = deadLetters.redrive(ID, SESSION);

        assertThat(summary.status()).isEqualTo("ARCHIVED");
        assertThat(summary.failureReason()).isEqualTo("SOMETHING_NEW");
    }

    @Test
    void 읽을_수_없거나_빈_응답은_연동_오류다() {
        server.expect(requestTo(BASE_URL + "/" + ID)).andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\":true,\"data\":{\"receiveCount\":\"many\""));
        server.expect(requestTo(BASE_URL + "/" + ID)).andRespond(ok("null"));

        assertThat(catchThrowable(() -> deadLetters.get(ID, SESSION))).isInstanceOf(IllegalStateException.class);
        assertThat(catchThrowable(() -> deadLetters.get(ID, SESSION))).isInstanceOf(IllegalStateException.class);
    }

    static final String SUMMARY = """
            {"deadLetterId":"%s","messageId":"m-1","eventId":"e-1","eventType":"ORDER_REFUND_REQUESTED",
             "orderId":"%s","failureReason":"PROCESSING_FAILED","receiveCount":5,"status":"OPEN","redrivable":true,
             "redrivenFromId":null,"sentAt":"2026-10-04T01:00:00Z","createdAt":"2026-10-04T01:10:00Z",
             "updatedAt":"2026-10-04T01:10:00Z"}""".formatted(ID, ORDER_ID);

    static final UUID BATCH_ID = UUID.fromString("00000000-0000-7000-8000-0000000000b1");

    static final String PROGRESS = """
            {"batchId":"%s","targetCount":3,"skippedCount":1,"sent":2,"unsent":1,"succeeded":1,"failed":0,
             "canceled":0,"pending":1,"startedAt":"2026-10-04T01:00:00Z","lastProgressAt":"2026-10-04T01:00:01Z",
             "finishedAt":null}""".formatted(BATCH_ID);

    static final String DETAIL = """
            {"deadLetterId":"%s","sourceQueue":"payment-events","messageId":"m-1","eventId":"e-1",
             "eventType":"ORDER_REFUND_REQUESTED","aggregateType":"ORDER","aggregateId":"%s","orderId":"%s",
             "failureReason":"PROCESSING_FAILED","receiveCount":5,"status":"OPEN","redrivable":true,"redrivenFromId":null,
             "body":"{}","sentAt":null,"redriveRequestedBy":null,"redriveStartedAt":null,"redrivenAt":null,"outcomeAt":null,
             "discardedBy":null,"discardedAt":null,"discardNote":null,"createdAt":"2026-10-04T01:10:00Z",
             "updatedAt":"2026-10-04T01:10:00Z"}""".formatted(ID, ORDER_ID, ORDER_ID);

    private static RequestMatcher forwardedToken() {
        return request -> assertThat(BearerTokens.parse(request.getHeaders().getFirst(BearerTokens.HEADER)))
                .contains(SESSION);
    }

    private static ResponseCreator ok(String data) {
        return withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(envelope(data));
    }

    private static ResponseCreator rejection(HttpStatus status, String code,
                                                                                  String details) {
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
