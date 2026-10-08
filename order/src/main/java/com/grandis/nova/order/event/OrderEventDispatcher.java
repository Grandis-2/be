package com.grandis.nova.order.event;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.DeferredRedelivery;
import com.grandis.nova.common.sqs.MessageHandling;
import com.grandis.nova.order.order.cancel.CancelSettlement;
import com.grandis.nova.order.order.cancel.RefundResults;
import com.grandis.nova.order.order.cancel.SettlePreorderCancelService;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.stock.admin.AdminStockService;
import com.grandis.nova.order.stock.api.StockRequest;
import com.grandis.nova.order.stock.domain.model.StockSetting;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/**
 * 받은 메시지를 이벤트 종류별 처리로 보낸다. 큐 소비기(common:sqs)는 본문을 그대로 여기에 넘긴다.
 *
 * 모르는 종류 · 깨진 본문 · 계약에 맞지 않는 값은 예외로 올린다. 건너뛰지 않는다 — 소비기는 메시지를 지우지 않고,
 * 재수신 한도를 넘으면 DLQ 로 간다. 지금 결과를 정할 수 없는 예약 취소만 {@link MessageHandling#DEFER} 로 알린다 —
 * 소비기가 늦춰 다시 받는다({@link DeferredRedelivery}).
 *
 * 재고 초기화(catalog 등록 이벤트)에는 관리자 인증이 없다. /api/v1/admin/** 보안 규칙을 거치지 않고 order-events 에 쓸 수 있는
 * 주체를 믿는다 — 큐 쓰기 권한이 신뢰 경계다. 잘못 들어와도 생성 전용(있는 재고는 덮지 않음) · 옵션 소속 · 판매 방식 검사로
 * 피해가 막힌다.
 */
@Component
public class OrderEventDispatcher {

    /** 예약 이벤트의 aggregate. aggregateId 가 예약 내부 id 라는 뜻이다. */
    static final String PREORDER_AGGREGATE = "PREORDER";
    /** 결제 결과의 aggregate. aggregateId 가 주문 id 라는 뜻이다. */
    static final String ORDER_AGGREGATE = "ORDER";
    /** catalog 상품 이벤트의 aggregate. aggregateId 가 상품 id 라는 뜻이다. */
    static final String PRODUCT_AGGREGATE = "PRODUCT";

    private final SettlePreorderCancelService cancelSettlement;
    private final PaymentResults paymentResults;
    private final RefundResults refundResults;
    private final AdminStockService stockService;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    public OrderEventDispatcher(SettlePreorderCancelService cancelSettlement, PaymentResults paymentResults,
                                RefundResults refundResults, AdminStockService stockService, JsonMapper jsonMapper,
                                Validator validator) {
        this.cancelSettlement = cancelSettlement;
        this.paymentResults = paymentResults;
        this.refundResults = refundResults;
        this.stockService = stockService;
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    /** @throws IllegalArgumentException 받지 않는 이벤트 종류 · aggregate · 계약에 맞지 않는 payload */
    public MessageHandling dispatch(String body) {
        EventEnvelope envelope = jsonMapper.readValue(body, EventEnvelope.class);
        return switch (InboundEventType.valueOf(envelope.eventType())) {
            case PREORDER_CANCEL_REQUESTED -> cancelSettlement.settle(
                    jsonMapper.treeToValue(envelope.payload(), PreorderCancelRequested.class)
                            .toCancel(aggregateId(envelope, PREORDER_AGGREGATE))) instanceof CancelSettlement.Deferred
                    ? MessageHandling.DEFER : MessageHandling.DONE;
            case ORDER_PAYMENT_SETTLED -> {
                paymentResults.settle(jsonMapper.treeToValue(envelope.payload(), OrderPaymentSettled.class)
                        .toSettlement(aggregateId(envelope, ORDER_AGGREGATE)));
                yield MessageHandling.DONE;
            }
            case ORDER_REFUND_SETTLED -> {
                refundResults.settle(jsonMapper.treeToValue(envelope.payload(), OrderRefundSettled.class)
                        .toSettlement(aggregateId(envelope, ORDER_AGGREGATE)));
                yield MessageHandling.DONE;
            }
            case IN_STOCK_PRODUCT_REGISTERED -> {
                stockService.initialize(aggregateId(envelope, PRODUCT_AGGREGATE), stockSettings(envelope));
                yield MessageHandling.DONE;
            }
        };
    }

    /** 대상은 봉투의 aggregateId 로 찾는다. 다른 aggregate 의 id 로 엉뚱한 대상을 바꾸지 않게 종류를 확인한다. */
    private static UUID aggregateId(EventEnvelope envelope, String expectedType) {
        if (!expectedType.equals(envelope.aggregateType()) || envelope.aggregateId() == null) {
            throw new IllegalArgumentException("%s 이벤트가 아니다: eventId=%s, aggregateType=%s, aggregateId=%s"
                    .formatted(expectedType, envelope.eventId(), envelope.aggregateType(), envelope.aggregateId()));
        }
        return envelope.aggregateId();
    }

    /**
     * 계약상 재고 초기화 API 본문과 같은 모양이라 같은 타입 · 같은 검증으로 푼다. 수량에 소수(12.5)가 오면 12 로
     * 잘리지 않고 여기서 실패한다. 같은 옵션이 두 번 오는 것은 서비스가 거른다.
     */
    private List<StockSetting> stockSettings(EventEnvelope envelope) {
        StockRequest request = jsonMapper.treeToValue(envelope.payload(), StockRequest.class);
        if (request == null) {
            throw new IllegalArgumentException("재고 payload 가 없다: eventId=" + envelope.eventId());
        }
        List<String> violations = validator.validate(request).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .toList();
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("재고 payload 가 계약에 맞지 않는다: eventId=%s, %s"
                    .formatted(envelope.eventId(), violations));
        }
        return request.toSettings();
    }
}
