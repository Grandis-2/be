package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.accept.AcceptResult;
import com.grandis.nova.preorder.accept.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.CancelStarter;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderRepository;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 큐 메시지 본문(계약 2.0 공통 봉투)을 종류별 처리로 보내는지. 본문은 계약 예시 모양 그대로다. */
@PreorderIntegrationTest
class PreorderEventDispatcherTest {

    @Autowired
    PreorderEventDispatcher dispatcher;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    CancelStarter cancelStarter;

    @Autowired
    PreorderRepository preorders;

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    Long preorderId;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer());
        preorderId = accepted.preorder().getId();
        token = AcceptFixtures.tokenOf(accepted);
    }

    @Test
    void 외부_작업_성공_메시지를_등록_반영으로_보낸다() {
        Long jobId = fixtures.workerSucceeds(preorderId, "REGISTER");
        String externalNumber = "R-" + ShopFixtures.unique();

        dispatcher.dispatch(envelope("EXTERNAL_JOB_SUCCEEDED", "PREORDER_SYNC_JOB", jobId, payload()
                .put("syncJobId", jobId)
                .put("preorderId", token)
                .put("jobType", "REGISTER")
                .put("externalNumber", externalNumber)));

        assertThat(jdbcTemplate.queryForObject("SELECT external_reference FROM preorders WHERE id = ?",
                String.class, preorderId)).isEqualTo(externalNumber);
    }

    @Test
    void 주문_정리_메시지를_정리_결과_처리로_보낸다() {
        cancelStarter.start(preorders.findById(preorderId).orElseThrow(), EventActor.USER, null, CancelReason.USER);

        dispatcher.dispatch(envelope("PREORDER_ORDER_SETTLED", "PREORDER", preorderId,
                settled("NO_ORDER").put("cancelSequence", fixtures.cancelSequence(preorderId))));

        assertThat(fixtures.count(
                "SELECT COUNT(*) FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'CANCEL'", preorderId))
                .isEqualTo(1);
    }

    @Test
    void 만료_요청_메시지를_만료_취소로_보낸다() {
        handler.onExternalJobSucceeded(new ExternalJobSucceeded(fixtures.workerSucceeds(preorderId, "REGISTER"),
                token, "REGISTER", "R-" + ShopFixtures.unique()));
        jdbcTemplate.update("UPDATE preorders SET payable_from = UTC_TIMESTAMP(6) - INTERVAL 25 HOUR WHERE id = ?",
                preorderId);

        dispatcher.dispatch(envelope("PREORDER_EXPIRY_REQUESTED", "PREORDER", preorderId,
                payload().put("preorderId", token)));

        assertThat(status()).isEqualTo("CANCELING");
    }

    @Test
    void 판매_중지_메시지를_회차_취소로_보낸다() {
        Long productId = preorders.findById(preorderId).orElseThrow().getProductId();

        dispatcher.dispatch(envelope("PREORDER_CAMPAIGN_CANCELED", "PRODUCT", productId,
                payload().put("productId", productId).put("reason", "공급 차질")));

        assertThat(status()).isEqualTo("CANCELING");
    }

    @Test
    void 받지_않는_이벤트_종류는_조용히_버리지_않고_예외로_올린다() {
        String body = envelope("SOMETHING_ELSE", "PREORDER", preorderId, payload());

        assertThatThrownBy(() -> dispatcher.dispatch(body)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 깨진_본문이나_모르는_결과_값이나_시도_순번_없는_결과는_예외로_올린다() {
        String unknownResult = envelope("PREORDER_ORDER_SETTLED", "PREORDER", preorderId,
                settled("MAYBE").put("cancelSequence", 2));
        String withoutSequence = envelope("PREORDER_ORDER_SETTLED", "PREORDER", preorderId, settled("CANCELED"));

        assertThatThrownBy(() -> dispatcher.dispatch("not-json")).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(unknownResult)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch(withoutSequence)).isInstanceOf(JacksonException.class);
    }

    /**
     * 계약 2.0 공통 봉투. 키는 계약서 이름을 그대로 적는다 — 받는 쪽 레코드를 직렬화해 만들면 이름이 어긋나도
     * 양쪽이 같이 바뀌어 잡지 못하고, 필드가 빠진 · 잘못된 메시지도 만들 수 없다.
     */
    private String envelope(String eventType, String aggregateType, Long aggregateId, ObjectNode payload) {
        ObjectNode envelope = jsonMapper.createObjectNode()
                .put("eventId", ShopFixtures.unique())
                .put("eventType", eventType)
                .put("aggregateType", aggregateType)
                .put("aggregateId", aggregateId)
                .put("occurredAt", "2026-09-03T01:00:03.470Z");
        envelope.set("payload", payload);
        return jsonMapper.writeValueAsString(envelope);
    }

    private String status() {
        return jdbcTemplate.queryForObject("SELECT status FROM preorders WHERE id = ?", String.class, preorderId);
    }

    private ObjectNode payload() {
        return jsonMapper.createObjectNode();
    }

    /** PREORDER_ORDER_SETTLED payload 에서 cancelSequence 를 뺀 부분. */
    private ObjectNode settled(String result) {
        return payload().put("preorderId", token).put("result", result).putNull("reason");
    }
}
