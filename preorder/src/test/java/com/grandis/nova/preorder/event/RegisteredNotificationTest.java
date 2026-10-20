package com.grandis.nova.preorder.event;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예약이 REGISTERED 가 될 때 같은 트랜잭션에서 회원 알림(PREORDER_REGISTERED)을 적는다.
 * member 는 eventId 로만 중복을 거르므로, 결과를 다시 받아도 아웃박스 행이 늘면 안 된다.
 */
@PreorderIntegrationTest
class RegisteredNotificationTest {

    static final String NOTIFICATION = "NOTIFICATION_REQUESTED";

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    CancelStarter cancelStarter;

    @Autowired
    Preorders preorders;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    UUID customerId;
    UUID preorderId;
    String token;
    UUID jobId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(customerId);
        preorderId = accepted.preorder().id();
        token = AcceptFixtures.tokenOf(accepted);
        jobId = fixtures.registerJob(preorderId);
    }

    @Test
    void 등록_확인으로_REGISTERED_가_되면_예약한_회원에게_알림_하나를_적는다() {
        handler.onExternalJobSucceeded(ShopFixtures.unique(), succeeded());

        List<String> payloads = notifications();
        assertThat(payloads).hasSize(1);
        assertThat(jsonMapper.readTree(payloads.getFirst())).isEqualTo(jsonMapper.readTree(
                "{\"recipientIds\":[\"" + customerId + "\"],\"kind\":\"PREORDER_REGISTERED\",\"title\":\"사전예약 등록 완료\","
                        + "\"body\":\"사전예약 등록이 완료됐어요. 24시간 안에 결제해 주세요.\","
                        + "\"target\":{\"type\":\"PREORDER\",\"id\":\"" + token + "\"}}"));
        assertThat(fixtures.count("""
                SELECT COUNT(*) FROM preorder_outbox_events
                 WHERE event_type = ? AND aggregate_id = ? AND aggregate_type = 'PREORDER'
                """, NOTIFICATION, UuidBinary.toBytes(preorderId))).as("원인은 예약 내부 id").isEqualTo(1);
    }

    /** 같은 결과가 다른 eventId 로 다시 와도(재전송 · worker 재시도) 전이가 없으면 적지 않는다. */
    @Test
    void 성공_결과를_다시_받아도_알림은_하나다() {
        handler.onExternalJobSucceeded(ShopFixtures.unique(), succeeded());
        handler.onExternalJobSucceeded(ShopFixtures.unique(), succeeded());

        assertThat(notifications()).hasSize(1);
    }

    @Test
    void 취소된_예약에_늦게_온_등록_성공은_알리지_않는다() {
        cancelStarter.start(preorders.findById(preorderId).orElseThrow(), EventActor.USER, null, CancelReason.USER);

        handler.onExternalJobSucceeded(ShopFixtures.unique(), succeeded());

        assertThat(notifications()).isEmpty();
    }

    private ExternalJobSucceeded succeeded() {
        return ShopFixtures.succeeded(jobId, token, "REGISTER", "EXT-" + ShopFixtures.unique());
    }

    private List<String> notifications() {
        return jdbcTemplate.queryForList("""
                SELECT payload FROM preorder_outbox_events WHERE event_type = ? AND aggregate_id = ? ORDER BY id
                """, String.class, NOTIFICATION, UuidBinary.toBytes(preorderId));
    }
}
