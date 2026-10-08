package com.grandis.nova.payment.sqs;

import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.recovery.RecoveryWorker;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.RecoveryCandidates;
import com.grandis.nova.payment.support.SqsIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * payment 의 SQS 배선을 끝에서 끝으로 본다 — 실제 SQS 프로토콜(Floci)로 payment-events 의 환불 요청을 받아 환불을 열고, 워커가
 * 보낸 환불의 결과가 order-events 로 나가는지, 처리할 수 없는 요청이 DLQ 로 가는지. 받기 · 지우기 · 다시 보이기 자체는 common:sqs 시험이 본다.
 */
@SqsIntegrationTest
class SqsMessagingTest {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final String QUEUE = "payment-events";
    static final Money AMOUNT = Money.won(15000);
    static final Instant APPROVED_AT = Instant.parse("2026-10-04T01:00:00Z");
    static final Instant CANCELED_AT = Instant.parse("2026-10-04T01:20:30Z");

    @Autowired
    TestQueues queues;

    @Autowired
    ApplicationContext context;

    @Autowired
    PaymentLedger ledger;

    @Autowired
    PaymentTransactionReader transactions;

    @Autowired
    RecoveryWorker worker;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    TossPaymentClient toss;

    PaymentTarget target;
    ProviderPaymentKey paymentKey;

    @BeforeEach
    void setUp() {
        target = PaymentFixtures.newOrderTarget();
        paymentKey = PaymentFixtures.newProviderPayment();
    }

    /** 설정(nova.sqs.consumer.enabled · transport=sqs)이 공통 소비기 · SQS 전송으로 이어진다. */
    @Test
    void 소비기와_전송이_공통_SQS_모듈로_엮인다() {
        assertThat(context.getBean("paymentEventConsumer")).isInstanceOf(RetryingQueueConsumer.class);
        assertThat(context.getBean("paymentEventConsumer", RetryingQueueConsumer.class).isRunning()).isTrue();
        assertThat(context.getBean(MessageTransport.class).getClass().getSimpleName()).isEqualTo("SqsMessageTransport");
    }

    /** 환불 요청 수신 → 환불 열림 → 워커가 토스 취소 → 환불 결과가 order-events 로 나간다. */
    @Test
    void 환불_요청을_받아_환불하면_결과가_order_events_로_나간다() {
        paid();
        RecoveryCandidates.parkOthers(jdbcTemplate);

        queues.send(QUEUE, refundRequested(target.id()));

        await().atMost(TIMEOUT).until(() -> !refunds().isEmpty());
        given(toss.cancel(any(), any())).willReturn(new TossCommandResult.Succeeded(new TossPayment(paymentKey.value(),
                "order", TossPaymentStatus.CANCELED, 15000, 0, "카드", APPROVED_AT, "tx",
                List.of(new TossPayment.Cancel(CANCELED_AT, 15000)))));
        worker.recoverDue();

        Message settled = queues.receive("order-events",
                m -> m.body().contains("ORDER_REFUND_SETTLED") && m.body().contains("\"aggregateId\":\"" + target.id() + "\""),
                TIMEOUT).orElseThrow();
        JsonNode body = jsonMapper.readTree(settled.body());
        assertThat(body.get("aggregateType").asString()).isEqualTo("ORDER");
        assertThat(body.get("payload").get("result").asString()).isEqualTo("REFUNDED");
        assertThat(settled.body()).doesNotContain(paymentKey.value());
    }

    /** 결제 기록이 없는 환불 요청은 다시 받아도 같다 — 지우지 않아 DLQ 로 간다. */
    @Test
    void 결제가_없는_환불_요청은_지우지_않아_DLQ_로_간다() {
        String body = refundRequested(target.id());

        queues.send(QUEUE, body);

        assertThat(queues.receive(QUEUE + "-dlq", m -> m.body().equals(body), TIMEOUT.multipliedBy(2))).isPresent();
        assertThat(refunds()).isEmpty();
    }

    private void paid() {
        PaymentTransaction pending = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
        ClaimedTransaction started = transactionTemplate.execute(s -> ledger.start(pending, target, paymentKey, AMOUNT))
                .orElseThrow();
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(started, new Outcome.Confirmed(APPROVED_AT)));
    }

    private List<PaymentTransaction> refunds() {
        return transactions.findTransactionsByTarget(target).stream()
                .filter(transaction -> transaction.type() == TransactionType.REFUND)
                .toList();
    }

    private static String refundRequested(UUID orderId) {
        return """
                {"eventId":"%s","eventType":"ORDER_REFUND_REQUESTED","aggregateType":"ORDER","aggregateId":"%s",\
                "occurredAt":"2026-10-04T01:00:00Z","payload":{"amount":15000}}""".formatted(UUID.randomUUID(), orderId);
    }
}
