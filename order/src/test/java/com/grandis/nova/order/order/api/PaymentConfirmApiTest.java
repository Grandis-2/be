package com.grandis.nova.order.order.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.ConfirmReply;
import com.grandis.nova.order.client.payment.ConfirmRequest;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.client.payment.PaymentConfirmClient;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.event.OrderEventDispatcher;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PreorderStubs;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/orders/{orderToken}/payment-attempts/{tossOrderId}/confirm — 결제 승인과 주문 반영. MySQL 위에서 돌고
 * preorder · payment 내부 API 만 대역이다(응답 모양 대조는 PaymentConfirmerTest). 결과 이벤트는 실제 분배기로 넣는다.
 *
 * 인증은 흉내({@link TestAuth})다 — Authorization 헤더의 값은 전달 확인용 자리일 뿐이다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PaymentConfirmApiTest {

    // 사용자가 보낸 액세스 토큰 자리. preorder · payment 에 그대로 전달돼야 한다. 실제 토큰 모양이 아니다.
    static final String SESSION = "payment-confirm-api-test";
    // 결제창이 돌려준 결제 키 자리. 로그에 남지 않는지도 본다. 실제 키 모양이 아니다.
    static final String PAYMENT = "tgen_confirm_api_20261002";
    // 저장된 총액. 수량 1 이라 단가와 같으므로 테스트에서 단가와 다르게 바꿔 둔다 — 단가를 보내는 실수를 잡으려고.
    static final BigDecimal TOTAL = new BigDecimal("1300000");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    OrderLedger ledger;

    @Autowired
    OrderEventDispatcher dispatcher;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    StockLedger stock;

    @MockitoBean
    PreorderClient preorderClient;

    @MockitoBean
    PaymentConfirmClient paymentClient;

    OrderFixtures fixtures;
    PreorderProduct product;
    UUID customerId;
    UUID preorderId;
    Order order;
    String attempt;
    /** 장바구니 주문 시험({@link #cartOrder})이 심은 옵션. */
    UUID cartOption;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.preorderProduct();
        customerId = fixtures.customer();
        preorderId = fixtures.payablePreorder(customerId, product, 1);
        order = new TransactionTemplate(transactionManager).execute(status -> ledger.place(
                OrderFixtures.preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user()));
        jdbcTemplate.update("UPDATE orders SET total_amount = ? WHERE id = ?", TOTAL, bytes(order.id()));
        attempt = UUID.randomUUID().toString();
        PreorderStubs.stub(preorderClient, PreorderStubs.payable(preorderId, order.preorderToken(), customerId, product));
    }

    // ── 승인 · 거절 · 확인 중 ───────────────────────────────────────────────

    @Test
    void approvedPaymentMovesOrderToAwaitingConfirmation(CapturedOutput output) throws Exception {
        paymentAnswers(reply(ConfirmReply.Result.APPROVED, null));

        confirm(attempt, TOTAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPROVED"))
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.declineReason").doesNotExist());

        // 대상 · 금액은 주문의 저장값이다(D15). 결제창 번호는 경로로, 사용자 토큰은 그대로 전달한다.
        // 승인 중으로 바꾸기 전에 결제창을 확인(시작 금지)하고, 그다음 시작한다
        InOrder calls = inOrder(paymentClient);
        calls.verify(paymentClient).confirm(attempt, new ConfirmRequest("ORDER", order.id(), PAYMENT, TOTAL, false, true),
                BearerTokens.value(SESSION));
        calls.verify(paymentClient).confirm(attempt, new ConfirmRequest("ORDER", order.id(), PAYMENT, TOTAL, true, false),
                BearerTokens.value(SESSION));
        assertThat(orderRow()).containsEntry("status", "AWAITING_CONFIRMATION")
                .containsEntry("authorizing_provider_order_id", null);
        assertThat(history()).containsExactly("1:null>AWAITING_PAYMENT:USER:null",
                "2:AWAITING_PAYMENT>AUTHORIZING:USER:null",
                "3:AUTHORIZING>AWAITING_CONFIRMATION:SYSTEM:PAYMENT_APPROVED");
        assertThat(applicationLogs(output)).noneMatch(line -> line.contains(PAYMENT) || line.contains(SESSION));
    }

    @Test
    void declinedPaymentReturnsOrderToAwaitingPaymentWithReason() throws Exception {
        paymentAnswers(reply(ConfirmReply.Result.DECLINED, DeclineReason.CARD_REJECTED));

        confirm(attempt, TOTAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.declineReason").value("CARD_REJECTED"));

        assertThat(orderRow()).containsEntry("status", "AWAITING_PAYMENT")
                .containsEntry("authorizing_provider_order_id", null);
        assertThat(history()).last().isEqualTo("3:AUTHORIZING>AWAITING_PAYMENT:SYSTEM:PAYMENT_DECLINED:CARD_REJECTED");
    }

    @Test
    void pendingPaymentKeepsOrderAuthorizing() throws Exception {
        paymentAnswers(reply(ConfirmReply.Result.PENDING, null));

        confirm(attempt, TOTAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"))
                .andExpect(jsonPath("$.data.orderStatus").value("AUTHORIZING"));

        assertThat(orderRow()).containsEntry("status", "AUTHORIZING")
                .containsEntry("authorizing_provider_order_id", attempt);
    }

    // payment 가 토스를 기다리는 사이 order 의 읽기 기한이 지났다 — 시작했을 수 있어 되돌리지 않는다
    @Test
    void paymentTimeoutKeepsOrderAuthorizing() throws Exception {
        paymentDoes(call -> {
            throw new ResourceAccessException("Read timed out");
        });

        confirm(attempt, TOTAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));

        assertThat(orderRow()).containsEntry("status", "AUTHORIZING");
    }

    // ── 앞으로도 시작될 수 없는 결제창: 주문을 결제 대기로 되돌린다 ─────────────────────────

    @Test
    void notStartableAttemptRevertsOrder() throws Exception {
        paymentRejects(HttpStatus.NOT_FOUND, "PAYMENT_ATTEMPT_NOT_FOUND");

        confirm(attempt, TOTAL)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ATTEMPT_NOT_FOUND"));

        assertThat(orderRow()).containsEntry("status", "AWAITING_PAYMENT")
                .containsEntry("authorizing_provider_order_id", null);
        assertThat(history()).last().isEqualTo("3:AUTHORIZING>AWAITING_PAYMENT:SYSTEM:PAYMENT_NOT_STARTED");
    }

    // ── 이번 요청만 거절 · 닿지 못함: 앞선 요청이 결제창을 이미 시작했을 수 있어 되돌리지 않는다 ─────────────

    // 동시 요청(더블 클릭)이 같은 결제창을 시작했을 수 있다
    @Test
    void startConflictKeepsOrderAuthorizing() throws Exception {
        paymentRejects(HttpStatus.CONFLICT, "PAYMENT_START_CONFLICT");

        confirm(attempt, TOTAL)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));

        assertThat(orderRow()).containsEntry("status", "AUTHORIZING").containsEntry("authorizing_provider_order_id", attempt);
    }

    @Test
    void unreachablePaymentKeepsOrderAuthorizing() throws Exception {
        paymentDoes(call -> {
            throw new ResourceAccessException("Connection refused", new ConnectException("Connection refused"));
        });

        confirm(attempt, TOTAL).andExpect(status().isServiceUnavailable());

        assertThat(orderRow()).containsEntry("status", "AUTHORIZING").containsEntry("authorizing_provider_order_id", attempt);
    }

    /*
     * 리뷰 High: 첫 요청이 결제창을 시작해 토스를 기다리는 사이 order 는 "확인 중"으로 답했고, 사용자의 재요청이 배포 중 연결 거부 ·
     * 순간 인증 실패 · 앞단 404 를 받았다. 이 신호는 재요청에 대한 것이지 결제창에 대한 것이 아니다 — 되돌리면 토스가 승인했을 때
     * 돈은 나갔는데 주문은 미결제가 된다.
     */
    @Test
    void retryRejectedForTransportReasonsKeepsOrderAuthorizing() throws Exception {
        fixtures.forceAuthorizing(order.id(), attempt);
        given(paymentClient.confirm(any(), any(), any()))
                .willThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null))
                .willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null))
                .willThrow(new ResourceAccessException("Connection refused", new ConnectException("Connection refused")));

        confirm(attempt, TOTAL).andExpect(status().isServiceUnavailable());
        confirm(attempt, TOTAL).andExpect(status().isInternalServerError());
        confirm(attempt, TOTAL).andExpect(status().isServiceUnavailable());

        assertThat(orderRow()).containsEntry("status", "AUTHORIZING").containsEntry("authorizing_provider_order_id", attempt);
        assertThat(history()).hasSize(1);
    }

    // ── 결제창 확인: payment 가 이 주문의 것으로 아는 결제창만 승인 중이 된다 ─────────────────────

    /*
     * 변조 · 남의 결제창 번호. 승인 중으로 바꾼 뒤에 알게 되면, 그 응답을 잃었을 때 payment 는 그 번호를 몰라 만료 · 복구 어느 것도
     * 주문을 풀지 못한다(승인 중에 갇힘). 바꾸기 전에 거절한다.
     */
    @Test
    void unknownAttemptIsRejectedBeforeAuthorizing() throws Exception {
        given(paymentClient.confirm(any(), any(), any()))
                .willThrow(rejection(HttpStatus.NOT_FOUND, "PAYMENT_ATTEMPT_NOT_FOUND"));

        confirm(attempt, TOTAL)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ATTEMPT_NOT_FOUND"));

        assertThat(orderRow()).containsEntry("status", "AWAITING_PAYMENT")
                .containsEntry("authorizing_provider_order_id", null);
        assertThat(history()).hasSize(1);
        verify(paymentClient, never()).confirm(any(), argThat(ConfirmRequest::startAllowed), any());
    }

    // 확인은 아무것도 시작하지 않는다 — 답을 받지 못하면(시작 호출과 달리 읽기 기한 · 5xx 도) 이번 요청 실패로 답하고 주문은 그대로
    @Test
    void checkWithoutAnswerChangesNothing() throws Exception {
        given(paymentClient.confirm(any(), any(), any()))
                .willThrow(new ResourceAccessException("Connection refused", new ConnectException("Connection refused")))
                .willThrow(new ResourceAccessException("Read timed out"))
                .willThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null,
                        null, null))
                .willReturn(ApiResponse.ok(new ConfirmReply(ConfirmReply.Result.DECLINED, null)));

        confirm(attempt, TOTAL).andExpect(status().isServiceUnavailable());
        confirm(attempt, TOTAL).andExpect(status().isServiceUnavailable());
        confirm(attempt, TOTAL).andExpect(status().isServiceUnavailable());
        confirm(attempt, TOTAL).andExpect(status().isInternalServerError());

        assertThat(orderRow()).containsEntry("status", "AWAITING_PAYMENT");
        assertThat(history()).hasSize(1);
        verify(paymentClient, never()).confirm(any(), argThat(ConfirmRequest::startAllowed), any());
    }

    // 이미 끝난 결제창(거절 · 만료): 승인 중을 거치지 않고 거절로 답한다
    @Test
    void finishedAttemptIsDeclinedWithoutAuthorizing() throws Exception {
        paymentAnswersEverything(reply(ConfirmReply.Result.DECLINED, DeclineReason.PAYMENT_EXPIRED));

        confirm(attempt, TOTAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.declineReason").value("PAYMENT_EXPIRED"));

        assertThat(history()).hasSize(1);
        verify(paymentClient, never()).confirm(any(), argThat(ConfirmRequest::startAllowed), any());
    }

    // 이미 승인된 결제창(앞선 응답 · 이벤트를 잃었다): 승인 중을 거쳐 승인을 반영한다
    @Test
    void approvedAttemptFoundByCheckIsApplied() throws Exception {
        paymentAnswersEverything(reply(ConfirmReply.Result.APPROVED, null));

        confirm(attempt, TOTAL)
                .andExpect(jsonPath("$.data.result").value("APPROVED"))
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_CONFIRMATION"));

        assertThat(history()).hasSize(3);
    }

    // ── 승인 전 검사: 아무것도 바꾸지 않는다 ───────────────────────────────────

    // D15: 사용자 금액은 기대값이 아니라 대조 대상이다
    @Test
    void bodyAmountDifferentFromTotalIsRejectedBeforeAnything() throws Exception {
        confirm(attempt, TOTAL.subtract(BigDecimal.ONE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_AMOUNT_MISMATCH"));

        verifyNoInteractions(preorderClient, paymentClient);
        assertThat(orderRow()).containsEntry("status", "AWAITING_PAYMENT");
    }

    @Test
    void someoneElsesOrderIsNotFound() throws Exception {
        perform(fixtures.customer(), order.orderToken().value(), attempt, body(PAYMENT, TOTAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));

        verifyNoInteractions(preorderClient, paymentClient);
    }

    @Test
    void expiredPreorderIsRejectedBeforePayment() throws Exception {
        PreorderStubs.stub(preorderClient,
                PreorderStubs.blocked(preorderId, order.preorderToken(), customerId, product, "DUE_PASSED"));

        confirm(attempt, TOTAL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_WINDOW_EXPIRED"));

        verifyNoInteractions(paymentClient);
        assertThat(orderRow()).containsEntry("status", "AWAITING_PAYMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELING", "CANCELED"})
    void canceledOrderIsNotPayable(String status) throws Exception {
        fixtures.forceStatus(order.id(), status);

        confirm(attempt, TOTAL)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_PAYABLE"));

        verifyNoInteractions(preorderClient, paymentClient);
    }

    // 주문당 성공 결제는 하나 — 결제된 주문의 재요청 · 폴링은 같은 답(APPROVED)을 받는다
    @ParameterizedTest
    @ValueSource(strings = {"AWAITING_CONFIRMATION", "SHIPPED"})
    void paidOrderAnswersApprovedWithoutCalls(String status) throws Exception {
        fixtures.forceStatus(order.id(), status);

        confirm(attempt, TOTAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPROVED"))
                .andExpect(jsonPath("$.data.orderStatus").value(status));

        verifyNoInteractions(preorderClient, paymentClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "tgen_bad\nline"})
    void paymentKeyBreakingProviderRulesIsRejected(String badKey) throws Exception {
        perform(customerId, order.orderToken().value(), attempt, body(badKey, TOTAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(preorderClient, paymentClient);
    }

    @Test
    void malformedTossOrderIdIsRejected() throws Exception {
        confirm("bad!", TOTAL).andExpect(status().isBadRequest());

        verifyNoInteractions(preorderClient, paymentClient);
    }

    // ── 승인 중 재요청: 같은 결제창이면 payment 에 다시 묻는다 ─────────────────────────

    // 결제창이 시작 전에 멈췄다면 재요청이 시작하므로 결제 가능을 다시 묻고, 가능하면 시작을 허용한다
    @Test
    void retryWhileAuthorizingRecoversResultFromPayment() throws Exception {
        fixtures.forceAuthorizing(order.id(), attempt);
        paymentAnswers(reply(ConfirmReply.Result.APPROVED, null));

        confirm(attempt, TOTAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPROVED"))
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_CONFIRMATION"));

        verify(paymentClient).confirm(eq(attempt), eq(new ConfirmRequest("ORDER", order.id(), PAYMENT, TOTAL, true, false)),
                any());
    }

    // 리뷰 Medium: 결제할 수 없게 된 주문의 재요청은 시작하지 않고 결과만 회수한다. 주문은 바꾸지 않는다(이미 시작됐을 수 있다)
    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "DUE_PASSED"})
    void retryForUnpayablePreorderOnlyRecoversResult(String reason) throws Exception {
        fixtures.forceAuthorizing(order.id(), attempt);
        PreorderStubs.stub(preorderClient,
                PreorderStubs.blocked(preorderId, order.preorderToken(), customerId, product, reason));
        paymentAnswers(reply(ConfirmReply.Result.PENDING, null));

        confirm(attempt, TOTAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));

        verify(paymentClient).confirm(eq(attempt), eq(new ConfirmRequest("ORDER", order.id(), PAYMENT, TOTAL, false, false)),
                any());
        assertThat(orderRow()).containsEntry("status", "AUTHORIZING").containsEntry("authorizing_provider_order_id", attempt);
    }

    /*
     * 결제 가능을 확인하지 못해도(preorder 장애 · 데이터 어긋남) 시작하지 않는다 — "확인 실패면 시작 허용" 으로 회귀하면 결제할 수
     * 없게 된 주문이 청구될 수 있다.
     */
    @Test
    void retryWhosePayabilityCannotBeCheckedOnlyRecoversResult() throws Exception {
        fixtures.forceAuthorizing(order.id(), attempt);
        given(preorderClient.getPayability(eq(order.preorderToken()), any()))
                .willThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null,
                        null, null))
                .willReturn(ApiResponse.ok(PreorderStubs.payable(UUID.randomUUID(), order.preorderToken(),
                        customerId, product)));
        paymentAnswers(reply(ConfirmReply.Result.PENDING, null));

        confirm(attempt, TOTAL).andExpect(jsonPath("$.data.result").value("PENDING"));
        confirm(attempt, TOTAL).andExpect(jsonPath("$.data.result").value("PENDING"));

        verify(paymentClient, times(2)).confirm(eq(attempt),
                eq(new ConfirmRequest("ORDER", order.id(), PAYMENT, TOTAL, false, false)), any());
        assertThat(orderRow()).containsEntry("status", "AUTHORIZING").containsEntry("authorizing_provider_order_id", attempt);
    }

    // 이미 시작된 결제창이면 결제할 수 없게 됐어도 결과는 회수해 반영한다
    @Test
    void retryForUnpayablePreorderStillAppliesRecoveredResult() throws Exception {
        fixtures.forceAuthorizing(order.id(), attempt);
        PreorderStubs.stub(preorderClient,
                PreorderStubs.blocked(preorderId, order.preorderToken(), customerId, product, "DUE_PASSED"));
        paymentAnswers(reply(ConfirmReply.Result.APPROVED, null));

        confirm(attempt, TOTAL).andExpect(jsonPath("$.data.result").value("APPROVED"));

        assertThat(orderRow()).containsEntry("status", "AWAITING_CONFIRMATION");
    }

    @Test
    void retryForAnotherAttemptWhileAuthorizingIsPending() throws Exception {
        fixtures.forceAuthorizing(order.id(), attempt);

        confirm(UUID.randomUUID().toString(), TOTAL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"))
                .andExpect(jsonPath("$.data.orderStatus").value("AUTHORIZING"));

        verifyNoInteractions(preorderClient, paymentClient);
    }

    // 더블 클릭: 첫 요청이 payment 를 기다리는 동안 둘째가 들어온다 — 둘째는 payment 에 다시 묻고(처리 중) PENDING 을 받는다
    @Test
    void doubleClickSecondRequestSeesPending() throws Exception {
        AtomicReference<String> second = new AtomicReference<>();
        AtomicBoolean firstStarted = new AtomicBoolean();
        paymentDoes(call -> {
            if (firstStarted.getAndSet(true)) {
                return reply(ConfirmReply.Result.PENDING, null);
            }
            second.set(confirm(attempt, TOTAL).andReturn().getResponse().getContentAsString());
            return reply(ConfirmReply.Result.APPROVED, null);
        });

        confirm(attempt, TOTAL).andExpect(jsonPath("$.data.result").value("APPROVED"));

        assertThat(jsonMapper.readTree(second.get()).get("data").get("result").asString()).isEqualTo("PENDING");
        verify(paymentClient, times(2)).confirm(eq(attempt), argThat(ConfirmRequest::startAllowed), any());
        assertThat(history()).hasSize(3);
    }

    // ── 동기 응답 · 결과 이벤트: 어느 순서로 와도 한 번만 반영 ───────────────────────────

    @Test
    void eventAfterSyncResultChangesNothing(CapturedOutput output) throws Exception {
        paymentAnswers(reply(ConfirmReply.Result.APPROVED, null));
        confirm(attempt, TOTAL).andExpect(status().isOk());

        dispatcher.dispatch(settled(attempt, "APPROVED", null));

        assertThat(orderRow()).containsEntry("status", "AWAITING_CONFIRMATION");
        assertThat(history()).hasSize(3);
        assertThat(output.getAll()).doesNotContain("주문이 받지 못했다");
    }

    @Test
    void eventBeforeSyncResultIsAppliedOnce(CapturedOutput output) throws Exception {
        paymentDoes(call -> {
            dispatcher.dispatch(settled(attempt, "APPROVED", null));
            return reply(ConfirmReply.Result.APPROVED, null);
        });

        confirm(attempt, TOTAL)
                .andExpect(jsonPath("$.data.result").value("APPROVED"))
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_CONFIRMATION"));

        assertThat(history()).hasSize(3);
        assertThat(output.getAll()).doesNotContain("주문이 받지 못했다");
    }

    @Test
    void declineEventBeforeSyncResultIsAppliedOnce() throws Exception {
        paymentDoes(call -> {
            dispatcher.dispatch(settled(attempt, "DECLINED", "CARD_REJECTED"));
            return reply(ConfirmReply.Result.DECLINED, DeclineReason.CARD_REJECTED);
        });

        confirm(attempt, TOTAL)
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_PAYMENT"));

        assertThat(history()).hasSize(3);
    }

    // 결과를 잃었던 확인 중 주문이 이벤트로 결과를 받는다
    @Test
    void eventSettlesOrderLeftAuthorizing() throws Exception {
        paymentAnswers(reply(ConfirmReply.Result.PENDING, null));
        confirm(attempt, TOTAL).andExpect(jsonPath("$.data.result").value("PENDING"));

        dispatcher.dispatch(settled(attempt, "DECLINED", "PAYMENT_EXPIRED"));

        assertThat(orderRow()).containsEntry("status", "AWAITING_PAYMENT")
                .containsEntry("authorizing_provider_order_id", null);
    }

    /*
     * 늦은 거절: X 거절 뒤 사용자가 새 결제창 Y 로 승인 중인데 X 의 거절 이벤트가 늦게 왔다. 반영하면 Y 진행 중에 결제 대기로
     * 돌아가 Y 의 승인을 받지 못한다(돈은 나갔는데 미결제). 무시하고, Y 의 승인은 그대로 반영된다.
     */
    @Test
    void lateDeclineOfEarlierAttemptDoesNotRevertCurrentAttempt() throws Exception {
        String earlier = attempt;
        paymentAnswers(reply(ConfirmReply.Result.DECLINED, DeclineReason.CARD_REJECTED));
        confirm(earlier, TOTAL).andExpect(jsonPath("$.data.result").value("DECLINED"));
        String current = UUID.randomUUID().toString();
        paymentAnswers(reply(ConfirmReply.Result.PENDING, null));
        confirm(current, TOTAL).andExpect(jsonPath("$.data.result").value("PENDING"));

        dispatcher.dispatch(settled(earlier, "DECLINED", "CARD_REJECTED"));

        assertThat(orderRow()).containsEntry("status", "AUTHORIZING").containsEntry("authorizing_provider_order_id", current);
        dispatcher.dispatch(settled(current, "APPROVED", null));
        assertThat(orderRow()).containsEntry("status", "AWAITING_CONFIRMATION");
    }

    // 돈은 나갔는데 주문이 받을 수 없는 상태 — 바꾸지 않고 ERROR 로 남긴다(환불은 NV-103). 예외로 올리지 않는다
    @Test
    void approvalForOrderNotAuthorizingIsLoggedAsError(CapturedOutput output) {
        dispatcher.dispatch(settled(attempt, "APPROVED", null));

        assertThat(orderRow()).containsEntry("status", "AWAITING_PAYMENT");
        assertThat(output.getAll()).contains("ERROR").contains("주문이 받지 못했다");
    }

    @Test
    void settledAmountDifferentFromTotalIsLoggedButApplied(CapturedOutput output) {
        fixtures.forceAuthorizing(order.id(), attempt);
        ObjectNode payload = payload(attempt, "APPROVED", null).put("amount", 1000);

        dispatcher.dispatch(envelope(payload));

        assertThat(orderRow()).containsEntry("status", "AWAITING_CONFIRMATION");
        assertThat(output.getAll()).contains("금액이 주문 총액과 다르다");
    }

    /** 애플리케이션 로그 줄만. MockMvc 가 찍는 요청 덤프(본문 포함)는 시험 도구의 출력이라 뺀다. */
    private static List<String> applicationLogs(CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains(" --- [order] ")).toList();
    }

    // ── 장바구니 주문 ───────────────────────────────────────────────────────

    /** 승인 요청 경로(동기 응답)로도 판매 확정 · 장바구니 차감이 같은 트랜잭션에서 한 번 일어난다. preorder 에 묻지 않는다. */
    @Test
    void approvedCartOrderSellsStockAndDeductsCart() throws Exception {
        Order cart = cartOrder(2);
        paymentAnswers(reply(ConfirmReply.Result.APPROVED, null));

        perform(customerId, cart.orderToken().value(), attempt, body(PAYMENT, cart.totalAmount().amount()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPROVED"))
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_CONFIRMATION"));

        assertThat(jdbcTemplate.queryForList("SELECT stock_reserved, stock_sold FROM option_inventories WHERE option_id = ?",
                (Object) bytes(cartOption))).containsExactly(Map.of("stock_reserved", 0, "stock_sold", 2));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_items WHERE customer_id = ?", Long.class,
                (Object) bytes(customerId))).isZero();
        verify(preorderClient, never()).getPayability(eq(cart.orderToken().value()), any());
    }

    /** 장바구니 주문은 주문의 기한(만든 때 + 10분)을 본다 — 지났으면 결제를 시작하지 않는다. */
    @Test
    void expiredCartOrderIsNotStarted() throws Exception {
        Order cart = cartOrder(1);
        // 저장은 UTC 다 — JVM 시간대로 바인딩되는 Timestamp 대신 DB 의 UTC 로 기한을 지나게 한다
        jdbcTemplate.update("UPDATE orders SET payment_due_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", (Object) bytes(cart.id()));

        perform(customerId, cart.orderToken().value(), attempt, body(PAYMENT, cart.totalAmount().amount()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_WINDOW_EXPIRED"));

        verifyNoInteractions(paymentClient);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, (Object) bytes(cart.id())))
                .isEqualTo("AWAITING_PAYMENT");
    }

    /** 승인 중 재요청인데 장바구니 주문의 기한이 지났다 — 시작하지 않고 결과만 회수한다(돈이 이미 나갔을 수 있어 주문은 그대로). */
    @Test
    void retryForExpiredCartOrderOnlyRecoversResult() throws Exception {
        Order cart = cartOrder(1);
        fixtures.forceAuthorizing(cart.id(), attempt);
        jdbcTemplate.update("UPDATE orders SET payment_due_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", (Object) bytes(cart.id()));
        paymentAnswers(reply(ConfirmReply.Result.PENDING, null));

        perform(customerId, cart.orderToken().value(), attempt, body(PAYMENT, cart.totalAmount().amount()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"));

        verify(paymentClient).confirm(eq(attempt),
                eq(new ConfirmRequest("ORDER", cart.id(), PAYMENT, cart.totalAmount().amount(), false, false)), any());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, (Object) bytes(cart.id())))
                .isEqualTo("AUTHORIZING");
    }

    /** 같은 회원의 장바구니 주문 — 장바구니 줄(무보증, quantity) · 재고 5 를 심고 확보까지 한 결제 대기 주문. */
    private Order cartOrder(int quantity) {
        OrderFixtures.StockProduct stocked = fixtures.inStockProduct(1);
        cartOption = stocked.optionIds().getFirst();
        fixtures.stock(cartOption, 5, 0, 0);
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(customerId), bytes(cartOption), quantity);
        PlaceOrderCommand command = new PlaceOrderCommand(customerId, OrderSource.CART, null, null, OrderFixtures.ADDRESS, List.of(
                new PlaceOrderCommand.Line(stocked.productId(), cartOption, quantity, new BigDecimal("1250000"), 0, BigDecimal.ZERO,
                        "아이폰 17", "블랙")));
        return new TransactionTemplate(transactionManager).execute(status -> {
            stock.reserve(Map.of(cartOption, quantity));
            return ledger.place(command.toDraft(), EventCause.user());
        });
    }

    private void paymentAnswers(ApiResponse<ConfirmReply> reply) {
        paymentDoes(call -> reply);
    }

    /** 결제창 확인까지 같은 답 — 결제창 확인 자체를 보는 테스트용. */
    private void paymentAnswersEverything(ApiResponse<ConfirmReply> reply) {
        given(paymentClient.confirm(any(), any(), any())).willReturn(reply);
    }

    private void paymentRejects(HttpStatus status, String code) {
        HttpClientErrorException rejected = rejection(status, code);
        paymentDoes(call -> {
            throw rejected;
        });
    }

    /**
     * payment 대역. 결제창 확인(시작 금지 + 확보)에는 "시작 전"(PENDING)으로 답하고 — payment 가 아는 결제창이다 —
     * 그 밖의 호출(시작 · 승인 중 결과 회수)은 answer 대로 답한다. 결제창 확인 자체를 보는 테스트는 직접 스텁한다.
     */
    private void paymentDoes(Answer<?> answer) {
        given(paymentClient.confirm(any(), any(), any())).willAnswer(call -> isCheck(call)
                ? reply(ConfirmReply.Result.PENDING, null)
                : answer.answer(call));
    }

    /** request 가 null 이면 다시 스텁하는 중이다(given(mock.confirm(any()…)) 이 앞 Answer 를 null 인자로 부른다). */
    private static boolean isCheck(InvocationOnMock call) {
        ConfirmRequest request = call.getArgument(1);
        return request != null && request.reserve();
    }

    private HttpClientErrorException rejection(HttpStatus status, String code) {
        String body = """
                {"success":false,"data":null,"error":{"code":"%s","message":"m","details":null}}
                """.formatted(code);
        HttpClientErrorException rejected = HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        // RestClient 가 붙이는 본문 변환을 흉내 낸다(직접 만든 예외에는 없다)
        rejected.setBodyConvertFunction(type -> jsonMapper.readValue(body, jsonMapper.constructType(type.getType())));
        return rejected;
    }

    private static ApiResponse<ConfirmReply> reply(ConfirmReply.Result result, DeclineReason reason) {
        return ApiResponse.ok(new ConfirmReply(result, reason));
    }

    private ResultActions confirm(String tossOrderId, BigDecimal amount) throws Exception {
        return perform(customerId, order.orderToken().value(), tossOrderId, body(PAYMENT, amount));
    }

    private ResultActions perform(UUID customer, String orderToken, String tossOrderId, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderToken}/payment-attempts/{tossOrderId}/confirm",
                orderToken, tossOrderId)
                .with(TestAuth.customer(customer))
                .header(BearerTokens.HEADER, BearerTokens.value(SESSION))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String body(String key, BigDecimal amount) {
        return jsonMapper.writeValueAsString(Map.of("paymentKey", key, "amount", amount));
    }

    /** payment outbox.OrderPaymentSettled 의 칸 이름 그대로(결제 키 없음). */
    private ObjectNode payload(String providerOrderId, String result, String declineReason) {
        ObjectNode payload = jsonMapper.createObjectNode()
                .put("providerOrderId", providerOrderId)
                .put("result", result)
                .put("amount", TOTAL);
        if ("APPROVED".equals(result)) {
            payload.put("approvedAt", "2026-10-02T03:04:05Z").putNull("declineReason");
        } else {
            payload.putNull("approvedAt").put("declineReason", declineReason);
        }
        return payload;
    }

    private String settled(String providerOrderId, String result, String declineReason) {
        return envelope(payload(providerOrderId, result, declineReason));
    }

    private String envelope(ObjectNode payload) {
        ObjectNode envelope = jsonMapper.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", "ORDER_PAYMENT_SETTLED")
                .put("aggregateType", "ORDER")
                .put("aggregateId", order.id().toString())
                .put("occurredAt", "2026-10-02T03:04:06Z");
        envelope.set("payload", payload);
        return jsonMapper.writeValueAsString(envelope);
    }

    private Map<String, Object> orderRow() {
        return jdbcTemplate.queryForMap("SELECT status, authorizing_provider_order_id FROM orders WHERE id = ?",
                bytes(order.id()));
    }

    /** "번호:from>to:actor:reason" 목록. 번호 순. */
    private List<String> history() {
        return jdbcTemplate.query("""
                SELECT event_sequence, from_status, to_status, actor, reason
                  FROM order_events WHERE order_id = ? ORDER BY event_sequence
                """, (rs, n) -> rs.getLong(1) + ":" + rs.getString(2) + ">" + rs.getString(3) + ":"
                + rs.getString(4) + ":" + rs.getString(5), bytes(order.id()));
    }
}
