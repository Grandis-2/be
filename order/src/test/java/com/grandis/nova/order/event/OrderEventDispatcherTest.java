package com.grandis.nova.order.event;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.sqs.MessageHandling;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.draw.DrawEntryPaymentResults;
import com.grandis.nova.order.draw.EntryPaymentSettlement;
import com.grandis.nova.order.order.cancel.CancelReason;
import com.grandis.nova.order.order.cancel.CancelSettlement;
import com.grandis.nova.order.order.cancel.RefundResults;
import com.grandis.nova.order.order.cancel.RefundSettlement;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelCommand;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelService;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.pay.PaymentSettlement;
import com.grandis.nova.order.outbox.PreorderOrderSettled;
import com.grandis.nova.order.stock.admin.AdminStockService;
import com.grandis.nova.order.stock.api.StockRequest;
import com.grandis.nova.order.stock.domain.model.StockSetting;
import com.grandis.nova.order.support.TestIds;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 큐 메시지 본문(공통 봉투)을 종류별 처리로 보내는지. 본문은 preorder · payment · catalog 가 보내는 모양 그대로다. */
class OrderEventDispatcherTest {

    static final UUID PREORDER_INTERNAL_ID = TestIds.id(50231);
    static final String PREORDER_UUID = "9f1c2d3e-0000-4000-8000-000000000001";
    static final UUID CUSTOMER_ID = TestIds.id(1024);
    static final UUID ORDER_ID = TestIds.id(7702);
    static final String PROVIDER_ORDER_ID = "5a1b2c3d-0000-4000-8000-000000000003";
    static final UUID PRODUCT_ID = TestIds.id(42);
    /** 봉투 · payload 원문에 넣는 JSON 문자열 토큰. */
    static final String PRODUCT = json(PRODUCT_ID);
    static final String OPTION_1 = json(TestIds.id(101));
    static final String OPTION_2 = json(TestIds.id(102));

    static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    final JsonMapper jsonMapper = JsonMapper.builder().build();
    final Validator validator = VALIDATORS.getValidator();
    SettlePreorderCancelService settlement;
    PaymentResults paymentResults;
    RefundResults refundResults;
    DrawEntryPaymentResults entryPaymentResults;
    AdminStockService stockService;
    OrderEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        settlement = mock(SettlePreorderCancelService.class);
        paymentResults = mock(PaymentResults.class);
        refundResults = mock(RefundResults.class);
        entryPaymentResults = mock(DrawEntryPaymentResults.class);
        stockService = mock(AdminStockService.class);
        dispatcher = new OrderEventDispatcher(settlement, paymentResults, refundResults, entryPaymentResults, stockService, jsonMapper,
                validator);
    }

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    @Test
    void 취소_요청은_봉투의_aggregateId_로_주문_정리에_넘긴다() {
        SettlePreorderCancelCommand cancel =
                new SettlePreorderCancelCommand(PREORDER_INTERNAL_ID, PREORDER_UUID, CUSTOMER_ID, CancelReason.USER, 3L);
        given(settlement.settle(cancel)).willReturn(new CancelSettlement.Settled(
                PreorderOrderSettled.canceled(PREORDER_INTERNAL_ID, PREORDER_UUID, 3L)));

        MessageHandling handling = dispatcher.dispatch(
                envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", PREORDER_INTERNAL_ID, cancelRequested()));

        verify(settlement).settle(cancel);
        assertThat(handling).isEqualTo(MessageHandling.DONE);
    }

    /** 지금 정할 수 없는 취소는 소비기가 늦춰 다시 받도록 알린다(지연 재발행). */
    @Test
    void 결과를_정할_수_없는_취소는_늦춰_다시_받도록_알린다() {
        given(settlement.settle(any())).willReturn(new CancelSettlement.Deferred(OrderStatus.CANCELING));

        MessageHandling handling = dispatcher.dispatch(
                envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", PREORDER_INTERNAL_ID, cancelRequested()));

        assertThat(handling).isEqualTo(MessageHandling.DEFER);
    }

    /** 환불 결과는 봉투의 aggregateId(주문 id)로 반영한다. 본문은 payment outbox.OrderRefundSettled 의 칸 이름 그대로다. */
    @Test
    void 환불_결과는_봉투의_aggregateId_로_환불_반영에_넘긴다() {
        dispatcher.dispatch(envelope("ORDER_REFUND_SETTLED", "ORDER", ORDER_ID, refunded()));
        dispatcher.dispatch(envelope("ORDER_REFUND_SETTLED", "ORDER", ORDER_ID, refundFailed()));

        verify(refundResults).settle(new RefundSettlement(ORDER_ID, RefundSettlement.Result.REFUNDED,
                new BigDecimal("15000")));
        verify(refundResults).settle(new RefundSettlement(ORDER_ID, RefundSettlement.Result.FAILED,
                new BigDecimal("15000")));
    }

    @Test
    void 주문이_아닌_aggregate_의_환불_결과나_결과와_맞지_않는_칸은_예외로_올린다() {
        String otherAggregate = envelope("ORDER_REFUND_SETTLED", "PREORDER", ORDER_ID, refunded());
        String failedWithTime = envelope("ORDER_REFUND_SETTLED", "ORDER", ORDER_ID,
                refundFailed().put("refundedAt", "2026-10-04T01:20:30Z"));
        String refundedWithoutTime = envelope("ORDER_REFUND_SETTLED", "ORDER", ORDER_ID,
                refunded().putNull("refundedAt"));

        assertThatThrownBy(() -> dispatcher.dispatch(otherAggregate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(failedWithTime)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(refundedWithoutTime)).isInstanceOf(JacksonException.class);
        verifyNoInteractions(refundResults);
    }

    @Test
    void 받지_않는_이벤트_종류는_조용히_버리지_않고_예외로_올린다() {
        String body = envelope("SOMETHING_ELSE", "PREORDER", PREORDER_INTERNAL_ID, cancelRequested());

        assertThatThrownBy(() -> dispatcher.dispatch(body)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(settlement, stockService);
    }

    /** 다른 aggregate 의 id 를 예약 내부 id 로 믿으면 엉뚱한 주문을 정리한다. */
    @Test
    void 예약이_아닌_aggregate_이거나_aggregateId_가_없으면_예외로_올린다() {
        String otherAggregate = envelope("PREORDER_CANCEL_REQUESTED", "ORDER", PREORDER_INTERNAL_ID, cancelRequested());
        String withoutId = envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", null, cancelRequested());

        assertThatThrownBy(() -> dispatcher.dispatch(otherAggregate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(withoutId)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(settlement);
    }

    @Test
    void 깨진_본문이나_모르는_사유나_시도_순번_없는_요청은_예외로_올린다() {
        String unknownReason = envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", PREORDER_INTERNAL_ID,
                cancelRequested().put("reason", "MAYBE"));
        ObjectNode noSequence = cancelRequested();
        noSequence.remove("cancelSequence");
        String withoutSequence = envelope("PREORDER_CANCEL_REQUESTED", "PREORDER", PREORDER_INTERNAL_ID, noSequence);

        assertThatThrownBy(() -> dispatcher.dispatch("not-json")).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(unknownReason)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(withoutSequence)).isInstanceOf(JacksonException.class);
        verifyNoInteractions(settlement);
    }

    /** 결제 결과는 봉투의 aggregateId(주문 id)로 반영한다. 본문은 payment 가 보내는 모양 그대로다. */
    @Test
    void 결제_결과는_봉투의_aggregateId_로_결과_반영에_넘긴다() {
        dispatcher.dispatch(envelope("ORDER_PAYMENT_SETTLED", "ORDER", ORDER_ID, approved()));
        dispatcher.dispatch(envelope("ORDER_PAYMENT_SETTLED", "ORDER", ORDER_ID, declined()));

        verify(paymentResults).settle(new PaymentSettlement(ORDER_ID, PROVIDER_ORDER_ID,
                PaymentSettlement.Result.APPROVED, new BigDecimal("15000"), null));
        verify(paymentResults).settle(new PaymentSettlement(ORDER_ID, PROVIDER_ORDER_ID,
                PaymentSettlement.Result.DECLINED, new BigDecimal("15000"), DeclineReason.CARD_REJECTED));
    }

    @Test
    void 주문이_아닌_aggregate_의_결제_결과나_결과와_맞지_않는_칸은_예외로_올린다() {
        String otherAggregate = envelope("ORDER_PAYMENT_SETTLED", "PREORDER", ORDER_ID, approved());
        String approvedWithReason = envelope("ORDER_PAYMENT_SETTLED", "ORDER", ORDER_ID,
                approved().put("declineReason", "FAILED"));
        ObjectNode noAttempt = approved();
        noAttempt.remove("providerOrderId");

        assertThatThrownBy(() -> dispatcher.dispatch(otherAggregate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(approvedWithReason)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(envelope("ORDER_PAYMENT_SETTLED", "ORDER", ORDER_ID, noAttempt)))
                .isInstanceOf(JacksonException.class);
        verifyNoInteractions(paymentResults);
    }

    @Test
    @DisplayName("응모비 결제 결과는 봉투의 aggregateId(응모 id)로 응모 결과 반영에 넘긴다 — 주문 결과 반영으로 가지 않는다")
    void drawEntryResultGoesToEntryResultsByEnvelopeAggregateId() {
        dispatcher.dispatch(envelope("DRAW_ENTRY_PAYMENT_SETTLED", "DRAW_ENTRY", ORDER_ID, approved()));
        dispatcher.dispatch(envelope("DRAW_ENTRY_PAYMENT_SETTLED", "DRAW_ENTRY", ORDER_ID, declined()));

        verify(entryPaymentResults).settle(new EntryPaymentSettlement(ORDER_ID, PROVIDER_ORDER_ID,
                PaymentSettlement.Result.APPROVED, new BigDecimal("15000"), null));
        verify(entryPaymentResults).settle(new EntryPaymentSettlement(ORDER_ID, PROVIDER_ORDER_ID,
                PaymentSettlement.Result.DECLINED, new BigDecimal("15000"), DeclineReason.CARD_REJECTED));
        verifyNoInteractions(paymentResults);
    }

    @Test
    @DisplayName("응모비 결과와 주문 결과는 aggregate 가 엇갈리면 예외 — 응모 id 로 주문을, 주문 id 로 응모를 바꾸지 않는다")
    void paymentResultWithCrossedAggregateIsRejected() {
        assertThatThrownBy(() -> dispatcher.dispatch(envelope("DRAW_ENTRY_PAYMENT_SETTLED", "ORDER", ORDER_ID, approved())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(envelope("ORDER_PAYMENT_SETTLED", "DRAW_ENTRY", ORDER_ID, approved())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentResults, entryPaymentResults);
    }

    // ── IN_STOCK_PRODUCT_REGISTERED ──
    // payload 는 손으로 노드를 만들지 않고 원문 JSON 으로 넣는다. 실제 메시지처럼 문자열 → 봉투 → JsonNode → StockRequest 를
    // 거쳐야, 12.5 · 1e3 이 어떤 노드로 들어와 정수 전용 칸을 만나는지까지 시험된다.

    @Test
    void 등록_이벤트는_봉투의_aggregateId_상품에_옵션별_초기_재고를_만든다() {
        MessageHandling handling = dispatcher.dispatch(registered("PRODUCT", PRODUCT, """
                {"items":[{"optionId":%s,"stockTotal":5},{"optionId":%s,"stockTotal":0}]}"""
                .formatted(OPTION_1, OPTION_2)));

        verify(stockService).initialize(PRODUCT_ID,
                List.of(new StockSetting(TestIds.id(101), 5), new StockSetting(TestIds.id(102), 0)));
        assertThat(handling).isEqualTo(MessageHandling.DONE);
    }

    /** 재고 초기화의 실패는 지연 재발행(DEFER)으로 바뀌지 않는다 — 영구 실패가 늦춰 다시 받기로 끝없이 돌지 않고 DLQ 로 간다. */
    @Test
    void 재고_초기화가_실패하면_DEFER_가_아니라_예외로_올린다() {
        given(stockService.initialize(any(), any())).willThrow(new BusinessException(OrderErrorCode.PRODUCT_NOT_FOUND));

        assertThatThrownBy(() -> dispatcher.dispatch(registered("PRODUCT", PRODUCT, """
                {"items":[{"optionId":%s,"stockTotal":5}]}""".formatted(OPTION_1))))
                .isInstanceOf(BusinessException.class);
    }

    /** 다른 aggregate 의 id 를 상품 id 로 믿으면 엉뚱한 상품에 재고를 만든다. */
    @ParameterizedTest
    @ValueSource(strings = {"\"PREORDER\",{id}", "\"product\",{id}", "null,{id}", "\"PRODUCT\",null"})
    void 상품이_아닌_aggregate_이거나_aggregateId_가_없으면_예외로_올린다(String typeAndId) {
        String[] parts = typeAndId.replace("{id}", PRODUCT).split(",");
        String body = """
                {"eventId":"0b6f3c9e-0000-4000-8000-000000000003","eventType":"IN_STOCK_PRODUCT_REGISTERED",
                 "aggregateType":%s,"aggregateId":%s,"occurredAt":"2026-10-02T03:00:00.123456Z",
                 "payload":{"items":[{"optionId":%s,"stockTotal":5}]}}""".formatted(parts[0], parts[1], OPTION_1);

        assertThatThrownBy(() -> dispatcher.dispatch(body)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(stockService);
    }

    /** 옵션 칸에 UUID 문자열이 아닌 것, 수량 칸에 정수 토큰이 아닌 것. 잘리거나 바뀌어 다른 값으로 들어가지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"optionId\":12.5,\"stockTotal\":5}",
            "{\"optionId\":12,\"stockTotal\":5}",
            "{\"optionId\":\"12\",\"stockTotal\":5}",
            "{\"optionId\":true,\"stockTotal\":5}",
            "{\"optionId\":{option},\"stockTotal\":10.7}",
            "{\"optionId\":{option},\"stockTotal\":1e3}",
            "{\"optionId\":{option},\"stockTotal\":\"5\"}",
            "{\"optionId\":{option},\"stockTotal\":true}",
            "{\"optionId\":{option},\"stockTotal\":2147483648}"
    })
    void 형식이_다른_값이_오면_잘라_넣지_않고_예외로_올린다(String item) {
        String body = registered("PRODUCT", PRODUCT, "{\"items\":[" + item.replace("{option}", OPTION_1) + "]}");

        assertThatThrownBy(() -> dispatcher.dispatch(body)).isInstanceOf(JacksonException.class);
        verifyNoInteractions(stockService);
    }

    /** 재고 API 와 같은 검증(StockRequest)이 이벤트에도 걸린다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"items\":null}",
            "{\"items\":[]}",
            "{\"items\":[null]}",
            "{\"items\":[{\"optionId\":null,\"stockTotal\":5}]}",
            "{\"items\":[{\"optionId\":{option},\"stockTotal\":null}]}",
            "{\"items\":[{\"stockTotal\":5}]}",
            "{\"items\":[{\"optionId\":{option}}]}",
            "{\"items\":[{\"optionId\":{option},\"stockTotal\":-1}]}"
    })
    void 재고_API_와_같은_검증에_걸리면_예외로_올린다(String payload) {
        String body = registered("PRODUCT", PRODUCT, payload.replace("{option}", OPTION_1));

        assertThatThrownBy(() -> dispatcher.dispatch(body)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(stockService);
    }

    @Test
    void 옵션이_상한보다_많거나_payload_가_없으면_예외로_올린다() {
        String tooMany = IntStream.rangeClosed(1, StockRequest.MAX_ITEMS + 1)
                .mapToObj(n -> "{\"optionId\":%s,\"stockTotal\":1}".formatted(json(TestIds.id(n))))
                .collect(Collectors.joining(",", "{\"items\":[", "]}"));

        assertThatThrownBy(() -> dispatcher.dispatch(registered("PRODUCT", PRODUCT, tooMany)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(registered("PRODUCT", PRODUCT, "null")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(stockService);
    }

    @Test
    void payload_키가_없거나_객체가_아니면_예외로_올린다() {
        String withoutPayload = """
                {"eventId":"0b6f3c9e-0000-4000-8000-000000000003","eventType":"IN_STOCK_PRODUCT_REGISTERED",
                 "aggregateType":"PRODUCT","aggregateId":%s,"occurredAt":"2026-10-02T03:00:00.123456Z"}"""
                .formatted(PRODUCT);

        assertThatThrownBy(() -> dispatcher.dispatch(withoutPayload)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(registered("PRODUCT", PRODUCT,
                "[{\"optionId\":%s,\"stockTotal\":5}]".formatted(OPTION_1)))).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(registered("PRODUCT", PRODUCT, "\"items\"")))
                .isInstanceOf(JacksonException.class);
        verifyNoInteractions(stockService);
    }

    /**
     * 공통 봉투. 키는 계약 이름을 그대로 적는다 — 받는 쪽 record 를 직렬화해 만들면 이름이 어긋나도
     * 양쪽이 같이 바뀌어 잡지 못한다.
     */
    private String envelope(String eventType, String aggregateType, UUID aggregateId, ObjectNode payload) {
        ObjectNode envelope = jsonMapper.createObjectNode()
                .put("eventId", "0b6f3c9e-0000-4000-8000-000000000002")
                .put("eventType", eventType)
                .put("aggregateType", aggregateType)
                .put("aggregateId", aggregateId == null ? null : aggregateId.toString())
                .put("occurredAt", "2026-09-03T01:00:03.470Z");
        envelope.set("payload", payload);
        return jsonMapper.writeValueAsString(envelope);
    }

    /** payment outbox.OrderPaymentSettled 의 칸 이름 그대로(결제 키 없음). */
    private ObjectNode approved() {
        return jsonMapper.createObjectNode()
                .put("providerOrderId", PROVIDER_ORDER_ID)
                .put("result", "APPROVED")
                .put("amount", 15000)
                .put("approvedAt", "2026-10-02T03:04:05Z")
                .putNull("declineReason");
    }

    private ObjectNode declined() {
        return jsonMapper.createObjectNode()
                .put("providerOrderId", PROVIDER_ORDER_ID)
                .put("result", "DECLINED")
                .put("amount", 15000)
                .putNull("approvedAt")
                .put("declineReason", "CARD_REJECTED");
    }

    /** payment outbox.OrderRefundSettled 의 칸 이름 그대로(결제 키 없음). */
    private ObjectNode refunded() {
        return jsonMapper.createObjectNode()
                .put("result", "REFUNDED")
                .put("amount", 15000)
                .put("refundedAt", "2026-10-04T01:20:30Z");
    }

    private ObjectNode refundFailed() {
        return jsonMapper.createObjectNode()
                .put("result", "FAILED")
                .put("amount", 15000)
                .putNull("refundedAt");
    }

    private ObjectNode cancelRequested() {
        return jsonMapper.createObjectNode()
                .put("preorderId", PREORDER_UUID)
                .put("customerId", CUSTOMER_ID.toString())
                .put("reason", "USER")
                .put("cancelSequence", 3L);
    }

    private static String json(UUID id) {
        return "\"" + id + "\"";
    }

    /** catalog 가 보내는 모양 그대로. aggregateId · payload 는 원문 JSON 이다. */
    private static String registered(String aggregateType, String aggregateId, String payload) {
        return """
                {"eventId":"0b6f3c9e-0000-4000-8000-000000000003","eventType":"IN_STOCK_PRODUCT_REGISTERED",
                 "aggregateType":"%s","aggregateId":%s,"occurredAt":"2026-10-02T03:00:00.123456Z",
                 "payload":%s}""".formatted(aggregateType, aggregateId, payload);
    }
}
