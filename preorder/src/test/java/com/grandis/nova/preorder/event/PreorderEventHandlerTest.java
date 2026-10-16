package com.grandis.nova.preorder.event;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 이벤트 처리. SQS 없이 메서드를 직접 부른다(계획서 P5-1). 같은 메시지를 두 번 받아도 결과가 같아야 한다. */
@PreorderIntegrationTest
class PreorderEventHandlerTest {

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    CancelStarter cancelStarter;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    Preorders preorders;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    UUID preorderId;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        AcceptResult accepted = accepts.accept(fixtures.customer());
        preorderId = accepted.preorder().id();
        token = AcceptFixtures.tokenOf(accepted);
    }

    @Test
    void 등록_성공이면_결제_가능이_되고_두_번_받아도_한_번만_반영된다() {
        UUID jobId = fixtures.workerSucceeds(preorderId, "REGISTER");
        String externalNumber = "R-" + ShopFixtures.unique();
        ExternalJobSucceeded message = ShopFixtures.succeeded(jobId, token, "REGISTER", externalNumber);

        handler.onExternalJobSucceeded(ShopFixtures.unique(), message);
        handler.onExternalJobSucceeded(ShopFixtures.unique(), message);

        assertThat(row()).containsEntry("status", "REGISTERED").containsEntry("external_reference", externalNumber);
        assertThat(eventCount()).isEqualTo(2);
    }

    @Test
    void 취소_중에_늦게_온_등록_성공은_반영하지_않는다() {
        startCancel(EventActor.USER);
        UUID jobId = fixtures.workerSucceeds(preorderId, "REGISTER");

        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(jobId, token, "REGISTER", "R-LATE"));

        assertThat(row()).containsEntry("status", "CANCELING").containsEntry("external_reference", null);
    }

    @Test
    void 주문_정리가_끝나면_외부_취소_작업을_하나만_만든다() {
        startCancel(EventActor.USER);
        PreorderOrderSettled settled = settled(PreorderOrderSettled.Result.NO_ORDER, null);

        handler.onOrderSettled(settled);
        handler.onOrderSettled(settled);

        Map<String, Object> job = jdbcTemplate.queryForMap("""
                SELECT id, status,
                       JSON_UNQUOTE(JSON_EXTRACT(request_payload, '$.externalKey')) AS external_key,
                       JSON_UNQUOTE(JSON_EXTRACT(request_payload, '$.reason')) AS reason
                  FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'CANCEL'
                """, (Object) UuidBinary.toBytes(preorderId));
        assertThat(job).containsEntry("status", "PENDING").containsEntry("external_key", token)
                .containsEntry("reason", "USER_CANCEL");
        assertThat(fixtures.count("""
                SELECT COUNT(*) FROM preorder_outbox_events WHERE event_type = 'CANCEL_JOB_READY' AND aggregate_id = ?
                """, job.get("id"))).isEqualTo(1);
    }

    @Test
    void 취소_중이_아니면_주문_정리_결과로_작업을_만들지_않는다() {
        handler.onOrderSettled(new PreorderOrderSettled(token, PreorderOrderSettled.Result.CANCELED, null, 1L, null));

        assertThat(fixtures.count("SELECT COUNT(*) FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'CANCEL'",
                (Object) UuidBinary.toBytes(preorderId))).isZero();
    }

    @Test
    void 주문이_취소를_거절하면_결제_가능으로_되돌리고_사유를_남긴다() {
        register();
        startCancel(EventActor.USER);

        handler.onOrderSettled(settled(PreorderOrderSettled.Result.REJECTED, "SHIPPED"));

        assertThat(row()).containsEntry("status", "REGISTERED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT reason FROM preorder_events WHERE preorder_id = ? ORDER BY event_sequence DESC LIMIT 1
                """, String.class, (Object) UuidBinary.toBytes(preorderId))).isEqualTo("ORDER_REJECTED:SHIPPED");
    }

    @Test
    void 외부_취소가_성공하면_취소_완료가_되고_재신청할_수_있게_된다() {
        startCancel(EventActor.ADMIN);
        handler.onOrderSettled(settled(PreorderOrderSettled.Result.NO_ORDER, null));
        UUID cancelJobId = fixtures.workerSucceeds(preorderId, "CANCEL");

        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(cancelJobId, token, "CANCEL", null));

        assertThat(row()).containsEntry("status", "CANCELED").containsEntry("active_marker", null);
    }

    /**
     * 만료 취소가 결제와 겹쳐 거절된 뒤 사용자가 다시 취소한 경우. 첫 시도의 거절이 다시 와도 두 번째 시도를
     * 되돌리지 않고, 두 번째 시도의 결과는 반영한다.
     */
    @Test
    void 이전_취소_시도의_결과가_다시_와도_지금_취소에는_반영하지_않는다() {
        register();
        startCancel(EventActor.SYSTEM);
        PreorderOrderSettled firstRejected = settled(PreorderOrderSettled.Result.REJECTED, "PAID");
        handler.onOrderSettled(firstRejected);
        startCancel(EventActor.USER);

        handler.onOrderSettled(firstRejected);
        assertThat(row()).containsEntry("status", "CANCELING");

        handler.onOrderSettled(settled(PreorderOrderSettled.Result.CANCELED, null));
        assertThat(fixtures.count("SELECT COUNT(*) FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'CANCEL'",
                (Object) UuidBinary.toBytes(preorderId))).isEqualTo(1);
    }

    @Test
    void 취소가_끝난_예약에_결제_확인이_오면_상태는_그대로_두고_경보로_센다() {
        register();
        startCancel(EventActor.USER);
        completeCancel(PreorderOrderSettled.Result.NO_ORDER);
        double before = paymentAfterCancel();

        handler.onPaymentConfirmed(new PreorderPaymentConfirmed(token, "o-1", Instant.now()));

        assertThat(row()).containsEntry("status", "CANCELED");
        assertThat(preorders.findReservedAt(preorderId)).isEmpty();
        assertThat(paymentAfterCancel() - before).isEqualTo(1);
    }

    /** 결제 → 환불로 취소된 예약에 취소 전 결제 확인이 다시 왔다. DB 가 버린 마이크로초 아래 자리는 같은 시각으로 본다. */
    @Test
    void 환불로_취소된_예약에_같은_결제_확인이_다시_오면_세지_않는다() {
        register();
        PreorderPaymentConfirmed confirmed =
                new PreorderPaymentConfirmed(token, "o-1", Instant.parse("2026-10-04T01:02:00.123456789Z"));
        handler.onPaymentConfirmed(confirmed);
        startCancel(EventActor.USER);
        completeCancel(PreorderOrderSettled.Result.CANCELED);
        double before = paymentAfterCancel();

        handler.onPaymentConfirmed(confirmed);

        assertThat(row()).containsEntry("status", "CANCELED");
        assertThat(paymentAfterCancel() - before).isZero();
    }

    @Test
    void 취소_중에_온_결제_확인은_시각만_남기고_세지_않는다() {
        register();
        startCancel(EventActor.USER);
        double before = paymentAfterCancel();

        handler.onPaymentConfirmed(new PreorderPaymentConfirmed(token, "o-1", Instant.parse("2026-10-04T01:02:00Z")));

        assertThat(row()).containsEntry("status", "CANCELING");
        assertThat(preorders.findReservedAt(preorderId)).contains(Instant.parse("2026-10-04T01:02:00Z"));
        assertThat(paymentAfterCancel() - before).isZero();
    }

    private void register() {
        handler.onExternalJobSucceeded(ShopFixtures.unique(), ShopFixtures.succeeded(fixtures.workerSucceeds(preorderId, "REGISTER"),
                token, "REGISTER", "R-" + ShopFixtures.unique()));
    }

    /** 주문 정리 결과 → 외부 취소 성공으로 CANCELED 까지 간다. */
    private void completeCancel(PreorderOrderSettled.Result result) {
        handler.onOrderSettled(settled(result, null));
        handler.onExternalJobSucceeded(ShopFixtures.unique(),
                ShopFixtures.succeeded(fixtures.workerSucceeds(preorderId, "CANCEL"), token, "CANCEL", null));
    }

    /** 기동 때 등록돼 있어야 한다 — 없으면 get 이 실패한다. 공유 컨텍스트라 앞뒤 차이로 본다. */
    private double paymentAfterCancel() {
        return meterRegistry.get(PreorderEventHandler.PAYMENT_AFTER_CANCEL_METRIC).counter().count();
    }

    private void startCancel(EventActor actor) {
        PreorderSnapshot preorder = preorders.findById(preorderId).orElseThrow();
        cancelStarter.start(preorder, actor, actor == EventActor.ADMIN ? "관리자 취소" : null,
                actor == EventActor.ADMIN ? CancelReason.ADMIN : CancelReason.USER);
    }

    /** order 가 지금 취소 시도에 답한 것처럼 만든다. */
    private PreorderOrderSettled settled(PreorderOrderSettled.Result result, String reason) {
        return new PreorderOrderSettled(token, result, reason, fixtures.cancelSequence(preorderId), null);
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap(
                "SELECT status, external_reference, active_marker FROM preorders WHERE id = ?",
                (Object) UuidBinary.toBytes(preorderId));
    }

    private int eventCount() {
        return fixtures.count("SELECT COUNT(*) FROM preorder_events WHERE preorder_id = ?",
                (Object) UuidBinary.toBytes(preorderId));
    }
}
