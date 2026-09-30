package com.grandis.nova.preorder.cancel;

import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.event.ExternalJobSucceeded;
import com.grandis.nova.preorder.event.PreorderEventHandler;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PreorderIntegrationTest
class ExpiryCancelServiceTest {

    @Autowired
    ExpiryCancelService expiryCancelService;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    CancelStarter cancelStarter;

    @Autowired
    Preorders preorders;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    Long preorderId;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer());
        preorderId = accepted.preorder().id();
        token = AcceptFixtures.tokenOf(accepted);
    }

    @Test
    void 결제_기한이_지난_예약은_주체_SYSTEM_사유_EXPIRY_로_취소를_시작하고_두_번_받아도_한_번이다() {
        makePayable(25);

        expiryCancelService.expire(token);
        expiryCancelService.expire(token);

        assertThat(status()).isEqualTo("CANCELING");
        Map<String, Object> event = jdbcTemplate.queryForMap("""
                SELECT actor, reason FROM preorder_events WHERE preorder_id = ? AND to_status = 'CANCELING'
                """, preorderId);
        assertThat(event).containsEntry("actor", "SYSTEM").containsEntry("reason", null);
        assertThat(jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reason')) FROM outbox_events
                 WHERE event_type = 'PREORDER_CANCEL_REQUESTED' AND aggregate_id = ?
                """, String.class, preorderId)).containsExactly("EXPIRY");
    }

    @Test
    void 만료가_동시에_두_번_와도_취소는_한_번만_시작된다() throws Exception {
        makePayable(25);

        List<Outcome<Object>> outcomes = Concurrently.run(2, i -> () -> {
            expiryCancelService.expire(token);
            return null;
        });

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertSingleCancelStart();
    }

    @Test
    void 만료와_사용자_취소가_겹쳐도_취소는_한_번만_시작된다() throws Exception {
        makePayable(25);

        List<Outcome<Object>> outcomes = Concurrently.run(2, i -> () -> {
            if (i == 0) {
                expiryCancelService.expire(token);
            } else {
                cancelStarter.start(preorders.findById(preorderId).orElseThrow(), EventActor.USER, null,
                        CancelReason.USER);
            }
            return null;
        });

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertSingleCancelStart();
    }

    @Test
    void 기한_전이면_무시한다() {
        makePayable(23);

        expiryCancelService.expire(token);

        assertThat(status()).isEqualTo("PAYABLE");
    }

    @Test
    void 아직_결제_가능이_아니면_무시한다() {
        expiryCancelService.expire(token);

        assertThat(status()).isEqualTo("PENDING_SYNC");
    }

    @Test
    void 없는_예약이면_예외로_올린다() {
        assertThatThrownBy(() -> expiryCancelService.expire(ShopFixtures.unique()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 외부 등록을 확인해 결제 가능으로 만들고, 결제 가능 시작을 그만큼 과거로 옮긴다. */
    private void makePayable(int hoursAgo) {
        handler.onExternalJobSucceeded(new ExternalJobSucceeded(fixtures.workerSucceeds(preorderId, "REGISTER"),
                token, "REGISTER", "R-" + ShopFixtures.unique()));
        jdbcTemplate.update("UPDATE preorders SET payable_from = UTC_TIMESTAMP(6) - INTERVAL ? HOUR WHERE id = ?",
                hoursAgo, preorderId);
    }

    private void assertSingleCancelStart() {
        assertThat(status()).isEqualTo("CANCELING");
        assertThat(fixtures.count("""
                SELECT COUNT(*) FROM preorder_events WHERE preorder_id = ? AND to_status = 'CANCELING'
                """, preorderId)).isEqualTo(1);
        assertThat(fixtures.count("""
                SELECT COUNT(*) FROM outbox_events WHERE event_type = 'PREORDER_CANCEL_REQUESTED' AND aggregate_id = ?
                """, preorderId)).isEqualTo(1);
    }

    private String status() {
        return jdbcTemplate.queryForObject("SELECT status FROM preorders WHERE id = ?", String.class, preorderId);
    }
}
