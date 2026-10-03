package com.grandis.nova.order.order.pay;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.payment.PaymentConfirmation;
import com.grandis.nova.order.client.payment.PaymentConfirmer;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.OrderToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.Semaphore;

/**
 * 결제 승인: 결제창 인증을 마친 결제를 payment 에 승인시키고 결과를 주문에 반영한다. 돈이 실제로 움직이는 단계다.
 *
 * 순서: 주문 확인(본인) → 금액 대조 → 동시 상한 → 결제 가능 재확인(트랜잭션 밖) → [tx] 승인 중 + 결제창 번호
 * → payment 승인(트랜잭션 밖) → [tx] 결과 반영({@link PaymentResults}).
 *
 * - 사용자가 보낸 금액은 기대값이 아니라 대조 대상이다(D15). 다르면 아무것도 바꾸지 않고 거절한다. payment 에는 주문의
 *   저장 총액이 간다(ConfirmRequest.of(order, …) — 금액을 따로 넘길 길이 없다).
 * - 결제 대기로 되돌리는 것은 payment 가 "이 결제창은 앞으로도 시작될 수 없다" 고 확언할 때뿐이다(PaymentConfirmer). 이번 요청이
 *   닿지 못했거나 거절됐으면 주문은 그대로 두고 오류로 답한다 — 앞선 요청이 같은 결제창을 이미 시작했을 수 있다.
 * - 승인 중인 주문에 같은 결제창으로 다시 오면 payment 에 다시 물어 결과를 회수한다. 결제창이 아직 시작 전이면 이 요청이 시작하므로
 *   결제 가능을 다시 확인하고, 결제할 수 없거나 확인하지 못하면 "시작 금지" 로 물어 결과만 받는다. 다른 결제창이면 확인 중이다.
 * - 시작 전에 멈춘 결제창의 주문은 확인 중에 남는다. 푸는 것은 NV-102 의 미시작 결제창 만료(EXPIRED — 스키마 · 상태 머신 ·
 *   도메인 불변식 변경 필요)다 — NV-102 없이 운영에 노출하지 않는다.
 * - 이미 결제된 주문이면 APPROVED 로 답한다(주문당 성공 결제는 하나).
 * - 승인 호출은 payment 응답을 최대 70초 기다린다. 동시 상한을 넘으면 상태를 바꾸기 전에 503 으로 거절한다 — 토스가 느려져도
 *   결제와 무관한 주문 API 의 요청 스레드가 남는다.
 * - 외부 호출 동안 잠금을 쥐지 않도록 트랜잭션은 짧게 연다. 바깥 트랜잭션 안에서 부르면 들어올 때 거절한다.
 *
 * 결제 키는 로그 · 예외에 싣지 않는다.
 */
@Service
public class ConfirmPaymentService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPaymentService.class);

    private final OrderReader orderReader;
    private final OrderLedger ledger;
    private final PayabilityGate payability;
    private final PaymentConfirmer confirmer;
    private final PaymentResults results;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Semaphore inFlight;

    /** @param maxConcurrency 인스턴스당 동시에 payment 승인을 기다리는 요청 수 상한(Tomcat 요청 스레드 200 중 일부) */
    ConfirmPaymentService(OrderReader orderReader, OrderLedger ledger, PayabilityGate payability,
                          PaymentConfirmer confirmer, PaymentResults results,
                          PlatformTransactionManager transactionManager,
                          @Value("${nova.payment-confirm.max-concurrency:50}") int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("nova.payment-confirm.max-concurrency 는 1 이상이어야 한다: " + maxConcurrency);
        }
        this.inFlight = new Semaphore(maxConcurrency);
        this.orderReader = orderReader;
        this.ledger = ledger;
        this.payability = payability;
        this.confirmer = confirmer;
        this.results = results;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * @param sessionToken    사용자가 보낸 액세스 토큰. preorder · payment 에 그대로 전달한다
     * @param providerOrderId 결제창(토스 orderId). 준비 때 받은 값
     * @param paymentKey      결제창이 돌려준 결제 키
     * @param amount          결제창이 돌려준 금액(사용자 입력). 주문 총액과 대조만 한다
     * @throws BusinessException ORDER_NOT_FOUND · ORDER_NOT_PAYABLE · PAYMENT_AMOUNT_MISMATCH · PAYMENT_ATTEMPT_NOT_FOUND ·
     *                           PREORDER_NOT_PAYABLE · PAYMENT_WINDOW_EXPIRED · UNAUTHENTICATED ·
     *                           DEPENDENCY_UNAVAILABLE(동시 상한 초과 포함)
     * @throws IllegalStateException 주문의 예약이 보이지 않거나 다른 예약 · 회원이다(데이터 어긋남), payment 연동 오류(500)
     */
    public ConfirmedPayment confirm(Long customerId, String sessionToken, String orderToken, String providerOrderId,
                                    String paymentKey, BigDecimal amount) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 승인은 트랜잭션 밖에서 불러야 한다 — 외부 호출 동안 잠금을 쥐지 않게");
        }
        Order order = findOwn(customerId, orderToken);
        if (order.totalAmount().amount().compareTo(amount) != 0) {
            throw new BusinessException(OrderErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (!inFlight.tryAcquire()) {
            log.warn("결제 승인 동시 상한 초과 — 상태를 바꾸지 않고 거절 orderId={}", order.id());
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            return proceed(order, sessionToken, providerOrderId, paymentKey, true);
        } finally {
            inFlight.release();
        }
    }

    /** @param mayStart 결제 대기면 승인을 시작해도 되는가. 시작이 경합에 지면 다시 읽어 한 번만 더 판정한다 */
    private ConfirmedPayment proceed(Order order, String sessionToken, String providerOrderId, String paymentKey,
                                     boolean mayStart) {
        return switch (order.status()) {
            case AWAITING_PAYMENT -> {
                if (!mayStart) {
                    throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
                }
                yield start(order, sessionToken, providerOrderId, paymentKey);
            }
            case AUTHORIZING -> providerOrderId.equals(order.authorizingProviderOrderId())
                    ? call(order, providerOrderId, paymentKey, sessionToken, startAllowed(order, sessionToken))
                    : ConfirmedPayment.pending();
            case AWAITING_CONFIRMATION, PREPARING_ITEMS, READY_TO_SHIP, SHIPPED, DELIVERED ->
                    ConfirmedPayment.approved(order.status());
            case CANCELING, CANCELED -> throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
        };
    }

    private ConfirmedPayment start(Order order, String sessionToken, String providerOrderId, String paymentKey) {
        payability.require(order, sessionToken);
        OrderTransition requested = writeTransaction.execute(status ->
                ledger.requestPayment(order.id(), providerOrderId, EventCause.user()));
        if (!requested.applied()) {
            // 읽은 뒤 다른 요청이 먼저 승인을 시작했거나 주문이 바뀌었다 — 지금 상태로 다시 판정한다
            return proceed(reload(order.id()), sessionToken, providerOrderId, paymentKey, false);
        }
        return call(order, providerOrderId, paymentKey, sessionToken, true);
    }

    /**
     * 승인 중 재요청이 결제창을 처음 시작해도 되는가. 앞선 요청이 시작 전에 멈췄다면 이 요청이 청구하므로 결제 가능을 다시 묻는다.
     * 결제할 수 없거나 확인하지 못하면(어떤 실패든) 시작하지 않고 결과만 회수한다 — 시작 금지는 돈을 움직이지 않으므로 늘 안전하다.
     * 주문은 바꾸지 않는다 — 결제창이 이미 시작됐을 수 있다.
     */
    private boolean startAllowed(Order order, String sessionToken) {
        try {
            payability.require(order, sessionToken);
            return true;
        } catch (BusinessException notPayable) {
            if (notPayable.errorCode().status() >= 500 || notPayable.errorCode() == CommonErrorCode.UNAUTHENTICATED) {
                log.warn("승인 중 재요청 — 결제 가능 확인 실패, 결과만 회수 orderId={} code={}",
                        order.id(), notPayable.errorCode().name());
            } else {
                log.info("승인 중 재요청 — 결제할 수 없음, 결과만 회수 orderId={} code={}",
                        order.id(), notPayable.errorCode().name());
            }
            return false;
        } catch (RuntimeException unknown) {
            log.error("승인 중 재요청 — 결제 가능 확인 실패(연동 오류 · 데이터 어긋남), 결과만 회수 orderId={}", order.id(), unknown);
            return false;
        }
    }

    private ConfirmedPayment call(Order order, String providerOrderId, String paymentKey, String sessionToken,
                                  boolean startAllowed) {
        return switch (confirmer.confirm(order, providerOrderId, paymentKey, sessionToken, startAllowed)) {
            case PaymentConfirmation.Approved approved ->
                    ConfirmedPayment.approved(results.approved(order.id(), providerOrderId).status());
            case PaymentConfirmation.Declined declined -> new ConfirmedPayment(ConfirmedPayment.Result.DECLINED,
                    results.declined(order.id(), providerOrderId, declined.reason()).status(), declined.reason());
            case PaymentConfirmation.Pending pending -> ConfirmedPayment.pending();
            case PaymentConfirmation.NotStartable notStartable -> {
                results.notStarted(order.id(), providerOrderId);
                throw notStartable.failure();
            }
            case PaymentConfirmation.Unanswered unanswered -> throw unanswered.failure();
        };
    }

    /** 본인 주문. 남의 주문은 존재를 숨긴다. */
    private Order findOwn(Long customerId, String orderToken) {
        Order order = readTransaction.execute(status -> OrderToken.parse(orderToken)
                        .flatMap(orderReader::findByOrderToken)
                        .filter(found -> found.customerId().equals(customerId)))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (order.source() != OrderSource.PREORDER) {
            throw new IllegalStateException("사전예약 주문만 결제할 수 있다: orderId=" + order.id());
        }
        return order;
    }

    private Order reload(Long orderId) {
        return readTransaction.execute(status -> orderReader.findById(orderId))
                .orElseThrow(() -> new IllegalStateException("주문이 사라졌다: " + orderId));
    }
}
