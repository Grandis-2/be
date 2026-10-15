package com.grandis.nova.preorder.event;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.CampaignCancelService;
import com.grandis.nova.preorder.cancel.ExpiryCancelService;
import com.grandis.nova.preorder.cancel.application.PreorderCancelService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.order.Cancelability;
import com.grandis.nova.preorder.integration.order.OrderClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 예약이 CANCELED 가 될 때 같은 트랜잭션에서 회원 알림(PREORDER_CANCELED)을 적는다.
 * 취소는 어느 주체로 시작하든 주문 정리 → 외부 취소 성공으로만 끝나므로, 시작 경로마다 끝까지 거쳐 문구를 본다.
 * order · worker 는 대역이고 그 결과를 이벤트 처리기에 직접 넣는다.
 */
@PreorderIntegrationTest
class CanceledNotificationTest {

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderCancelService cancelService;

    @Autowired
    ExpiryCancelService expiryCancelService;

    @Autowired
    CampaignCancelService campaignCancelService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    CatalogClient catalogClient;

    @MockitoBean
    OrderClient orderClient;

    ShopFixtures fixtures;
    UUID customerId;
    UUID preorderId;
    UUID productId;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(customerId);
        preorderId = accepted.preorder().id();
        productId = accepted.preorder().productId();
        token = AcceptFixtures.tokenOf(accepted);
        given(orderClient.getCancelability(any())).willReturn(ApiResponse.ok(new Cancelability(null, true, null)));
    }

    @Test
    void 사용자_취소가_끝나면_예약한_회원에게_알림_하나를_적는다() {
        register();
        cancelService.cancelByCustomer(customerId, token, null);

        complete(PreorderOrderSettled.Result.NO_ORDER);

        List<String> payloads = notifications();
        assertThat(payloads).hasSize(1);
        assertThat(jsonMapper.readTree(payloads.getFirst())).isEqualTo(jsonMapper.readTree(
                "{\"recipientIds\":[\"" + customerId + "\"],\"kind\":\"PREORDER_CANCELED\",\"title\":\"사전예약 취소\","
                        + "\"body\":\"사전예약 취소가 완료됐어요.\","
                        + "\"target\":{\"type\":\"PREORDER\",\"id\":\"" + token + "\"}}"));
        assertThat(fixtures.count("""
                SELECT COUNT(*) FROM preorder_outbox_events
                 WHERE event_type = 'NOTIFICATION_REQUESTED' AND aggregate_id = ? AND aggregate_type = 'PREORDER'
                   AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.kind')) = 'PREORDER_CANCELED'
                """, (Object) UuidBinary.toBytes(preorderId))).as("원인은 예약 내부 id").isEqualTo(1);
    }

    /** 같은 결과가 다른 eventId 로 다시 와도(재전송 · worker 재시도) 이미 CANCELED 라 전이가 없으면 적지 않는다. */
    @Test
    void 외부_취소_성공을_다시_받아도_알림은_하나다() {
        register();
        cancelService.cancelByCustomer(customerId, token, null);
        complete(PreorderOrderSettled.Result.CANCELED);

        handler.onExternalJobSucceeded(ShopFixtures.unique(), cancelSucceeded());

        assertThat(notifications()).hasSize(1);
    }

    @Test
    void 외부_등록_전에_취소해도_외부_취소를_거쳐_끝날_때_알린다() {
        cancelService.cancelByCustomer(customerId, token, null);

        complete(PreorderOrderSettled.Result.NO_ORDER);

        assertThat(bodies()).containsExactly("사전예약 취소가 완료됐어요.");
    }

    /** 결제된 주문은 환불이 끝나야 CANCELED 로 정리되므로 끝난 시점엔 환불이 끝나 있다. */
    @Test
    void 결제된_예약이면_환불됐다는_문장을_붙인다() {
        register();
        handler.onPaymentConfirmed(new PreorderPaymentConfirmed(token, ShopFixtures.unique(), Instant.now()));
        cancelService.cancelByCustomer(customerId, token, null);

        complete(PreorderOrderSettled.Result.CANCELED);

        assertThat(bodies()).containsExactly("사전예약 취소가 완료됐어요. 결제하신 금액은 환불됐어요.");
    }

    @Test
    void 관리자_취소는_주체를_밝히지_않는다() {
        register();
        cancelService.cancelByAdmin(token, "재고 부족");

        complete(PreorderOrderSettled.Result.CANCELED);

        assertThat(bodies()).containsExactly("사전예약이 취소됐어요.");
    }

    @Test
    void 결제_기한_만료_취소는_기한을_알린다() {
        register();
        fixtures.passPaymentDue(preorderId);
        expiryCancelService.expire(token);

        complete(PreorderOrderSettled.Result.CANCELED);

        assertThat(bodies()).containsExactly("결제 기한이 지나 사전예약이 취소됐어요.");
    }

    @Test
    void 회차_판매_중지_취소는_관리자_취소와_문구가_같다() {
        register();
        campaignCancelService.cancel(productId, "공급 차질");

        complete(PreorderOrderSettled.Result.NO_ORDER);

        assertThat(bodies()).containsExactly("사전예약이 취소됐어요.");
    }

    @Test
    void 취소가_거절되어_되돌아가면_알리지_않는다() {
        register();
        cancelService.cancelByCustomer(customerId, token, null);

        handler.onOrderSettled(new PreorderOrderSettled(token, PreorderOrderSettled.Result.REJECTED, "SHIPPED",
                fixtures.cancelSequence(preorderId), null));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM preorders WHERE id = ?", String.class,
                (Object) UuidBinary.toBytes(preorderId))).isEqualTo("REGISTERED");
        assertThat(notifications()).isEmpty();
    }

    /** 문구는 가장 최근 취소 시도의 주체로 고른다 — 사용자 취소가 거절된 뒤 관리자가 다시 취소하면 관리자 문구다. */
    @Test
    void 거절된_뒤_다시_취소하면_최근_시도의_주체로_문구를_고른다() {
        register();
        cancelService.cancelByCustomer(customerId, token, null);
        handler.onOrderSettled(new PreorderOrderSettled(token, PreorderOrderSettled.Result.REJECTED, "SHIPPED",
                fixtures.cancelSequence(preorderId), null));
        cancelService.cancelByAdmin(token, "재고 부족");

        complete(PreorderOrderSettled.Result.CANCELED);

        assertThat(bodies()).containsExactly("사전예약이 취소됐어요.");
    }

    private void register() {
        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(
                fixtures.workerSucceeds(preorderId, "REGISTER"), token, "REGISTER", "R-" + ShopFixtures.unique()));
    }

    /** 주문 정리 결과 → 외부 취소 성공. 지금 취소 시도의 순번을 실어 보낸다. */
    private void complete(PreorderOrderSettled.Result result) {
        handler.onOrderSettled(new PreorderOrderSettled(token, result, null, fixtures.cancelSequence(preorderId), null));
        handler.onExternalJobSucceeded(ShopFixtures.unique(), cancelSucceeded());
    }

    private ExternalJobSucceeded cancelSucceeded() {
        return ShopFixtures.succeeded(fixtures.workerSucceeds(preorderId, "CANCEL"), token, "CANCEL", null);
    }

    private List<String> bodies() {
        return notifications().stream().map(payload -> jsonMapper.readTree(payload).get("body").asString()).toList();
    }

    private List<String> notifications() {
        return jdbcTemplate.queryForList("""
                SELECT payload FROM preorder_outbox_events
                 WHERE event_type = 'NOTIFICATION_REQUESTED' AND aggregate_id = ?
                   AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.kind')) = 'PREORDER_CANCELED'
                 ORDER BY id
                """, String.class, (Object) UuidBinary.toBytes(preorderId));
    }
}
