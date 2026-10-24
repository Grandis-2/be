package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사전예약 주문의 결제 실패 알림(PREORDER_PAYMENT_FAILED) — 결과 이벤트로 받은 거절을 반영할 때만 한 건. 승인 응답으로 바로 받은 거절 ·
 * "시작될 수 없음" 확언은 화면이 이미 보였으므로 보내지 않는다. 테스트마다 새 예약을 써서 아웃박스 행이 겹치지 않는다.
 */
@OrderIntegrationTest
class PreorderPaymentFailureNoticeTest {

    static final String NOTICE = "NOTIFICATION_REQUESTED";

    @Autowired PaymentResults results;
    @Autowired OrderLedger ledger;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JsonMapper jsonMapper;

    UUID customerId;
    Order order;
    String attempt;

    @BeforeEach
    void setUp() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        order = new PlacedOrders(ledger, transactionTemplate, fixtures).place(customerId);
        attempt = OrderFixtures.unique();
        transactionTemplate.executeWithoutResult(status -> ledger.requestPayment(order.id(), attempt, EventCause.user()));
    }

    @Test
    void 결과_이벤트의_거절이면_결제_대기로_되돌리고_예약으로_가는_알림을_한_건_적는다() {
        results.settle(declined());
        results.settle(declined());

        assertThat(statusOf()).isEqualTo("AWAITING_PAYMENT");
        List<JsonNode> notices = notices();
        assertThat(notices).hasSize(1);
        JsonNode notice = notices.getFirst();
        assertThat(notice.get("kind").asString()).isEqualTo("PREORDER_PAYMENT_FAILED");
        assertThat(notice.get("title").asString()).isEqualTo("결제 실패");
        assertThat(notice.get("body").asString()).isEqualTo("결제가 완료되지 않았어요. 결제 기한 안에 다시 결제해 주세요.");
        assertThat(notice.get("recipientIds").get(0).asString()).isEqualTo(customerId.toString());
        assertThat(notice.get("target").get("type").asString()).isEqualTo("PREORDER");
        assertThat(notice.get("target").get("id").asString()).isEqualTo(order.preorderToken());
    }

    @Test
    void 승인_응답의_거절은_알리지_않는다() {
        results.declined(order.id(), attempt, DeclineReason.CARD_REJECTED);

        assertThat(statusOf()).isEqualTo("AWAITING_PAYMENT");
        assertThat(notices()).isEmpty();
    }

    @Test
    void 시작될_수_없다는_확언은_알리지_않는다() {
        results.notStarted(order.id(), attempt);

        assertThat(statusOf()).isEqualTo("AWAITING_PAYMENT");
        assertThat(notices()).isEmpty();
    }

    @Test
    void 다른_결제창의_거절은_반영하지_않아_알리지도_않는다() {
        results.settle(new PaymentSettlement(order.id(), "other-" + attempt, PaymentSettlement.Result.DECLINED,
                order.totalAmount().amount(), null, DeclineReason.CARD_REJECTED));

        assertThat(statusOf()).isEqualTo("AUTHORIZING");
        assertThat(notices()).isEmpty();
    }

    private PaymentSettlement declined() {
        return new PaymentSettlement(order.id(), attempt, PaymentSettlement.Result.DECLINED, order.totalAmount().amount(), null,
                DeclineReason.PAYMENT_EXPIRED);
    }

    private String statusOf() {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, bytes(order.id()));
    }

    private List<JsonNode> notices() {
        return jdbcTemplate.queryForList("""
                SELECT payload FROM order_outbox_events
                 WHERE aggregate_type = 'ORDER' AND aggregate_id = ? AND event_type = ? ORDER BY id
                """, String.class, bytes(order.id()), NOTICE).stream().map(jsonMapper::readTree).toList();
    }
}
