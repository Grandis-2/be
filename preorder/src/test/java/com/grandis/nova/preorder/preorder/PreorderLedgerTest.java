package com.grandis.nova.preorder.preorder;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.preorder.PreorderFact.CancelCompleted;
import com.grandis.nova.preorder.preorder.PreorderFact.CancelRejected;
import com.grandis.nova.preorder.preorder.PreorderFact.CancelRequested;
import com.grandis.nova.preorder.preorder.PreorderFact.PaymentConfirmed;
import com.grandis.nova.preorder.preorder.PreorderFact.PaymentStarted;
import com.grandis.nova.preorder.preorder.PreorderFact.RegisterConfirmed;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.grandis.nova.preorder.preorder.PreorderStatus.CANCELING;
import static com.grandis.nova.preorder.preorder.PreorderStatus.PENDING_SYNC;
import static com.grandis.nova.preorder.preorder.PreorderStatus.REGISTERED;
import static com.grandis.nova.preorder.preorder.PreorderStatus.RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PreorderIntegrationTest
@Transactional
class PreorderLedgerTest {

    @Autowired
    PreorderLedger ledger;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    ShopFixtures fixtures;
    PreorderProduct product;
    UUID customerId;
    final AtomicLong nextPosition = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        product = fixtures.openPreorderProduct();
        customerId = fixtures.customer();
    }

    @Test
    void 접수하면_PENDING_SYNC_와_첫_이력이_남고_활성으로_표시된다() {
        PreorderSnapshot preorder = ledger.accept(draft(customerId), EventActor.USER, null);

        assertThat(preorder.id()).isNotNull();
        assertThat(row(preorder.id()))
                .containsEntry("status", "PENDING_SYNC")
                .containsEntry("event_sequence", 1L)
                .containsEntry("active_marker", 1);
        assertThat(history(preorder.id())).containsExactly("1:null>PENDING_SYNC:USER");
    }

    @Test
    void 사건이_적용되면_이력_번호가_하나_오르고_이력이_남는다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();

        PreorderTransition result = ledger.fire(id, new CancelRequested(EventActor.USER, null));

        assertThat(result).isEqualTo(new PreorderTransition(true, CANCELING));
        assertThat(row(id)).containsEntry("status", "CANCELING").containsEntry("event_sequence", 2L);
        assertThat(history(id)).containsExactly("1:null>PENDING_SYNC:USER", "2:PENDING_SYNC>CANCELING:USER");
    }

    @Test
    void 지금_상태에서_의미_없는_사건이면_아무것도_바꾸지_않는다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();

        PreorderTransition result = ledger.fire(id, new CancelCompleted());

        assertThat(result).isEqualTo(new PreorderTransition(false, PENDING_SYNC));
        assertThat(row(id)).containsEntry("status", "PENDING_SYNC").containsEntry("event_sequence", 1L);
        assertThat(history(id)).hasSize(1);
    }

    @Test
    void 취소를_두_번_요청해도_한_번만_반영된다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();
        ledger.fire(id, new CancelRequested(EventActor.USER, null));

        PreorderTransition again = ledger.fire(id, new CancelRequested(EventActor.USER, null));

        assertThat(again).isEqualTo(new PreorderTransition(false, CANCELING));
        assertThat(history(id)).hasSize(2);
    }

    @Test
    void 등록_확인은_한_번만_되고_결제_기한_기준_시각을_찍는다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();
        String externalReference = "EXT-" + ShopFixtures.unique();

        assertThat(ledger.fire(id, new RegisterConfirmed(externalReference))).isEqualTo(new PreorderTransition(true, REGISTERED));
        Object payableFrom = row(id).get("payable_from");
        assertThat(ledger.fire(id, new RegisterConfirmed("EXT-" + ShopFixtures.unique())))
                .isEqualTo(new PreorderTransition(false, REGISTERED));

        assertThat(payableFrom).isNotNull();
        assertThat(row(id))
                .containsEntry("status", "REGISTERED")
                .containsEntry("external_reference", externalReference)
                .containsEntry("payable_from", payableFrom);
        assertThat(history(id)).containsExactly("1:null>PENDING_SYNC:USER", "2:PENDING_SYNC>REGISTERED:SYSTEM");
    }

    @Test
    void 취소_중인_예약에_늦게_온_등록_확인은_반영하지_않는다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();
        ledger.fire(id, new CancelRequested(EventActor.USER, null));

        assertThat(ledger.fire(id, new RegisterConfirmed("EXT-LATE"))).isEqualTo(new PreorderTransition(false, CANCELING));

        assertThat(row(id)).containsEntry("status", "CANCELING").containsEntry("payable_from", null);
    }

    @Test
    void REGISTERED_에서_시작한_취소가_거절되면_REGISTERED_로_되돌린다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();
        ledger.fire(id, new RegisterConfirmed("EXT-" + ShopFixtures.unique()));
        ledger.fire(id, new CancelRequested(EventActor.USER, null));

        PreorderTransition result = ledger.fire(id, new CancelRejected("SHIPPING_STARTED", null));

        assertThat(result).isEqualTo(new PreorderTransition(true, REGISTERED));
        assertThat(history(id)).last().isEqualTo("4:CANCELING>REGISTERED:SYSTEM");
        assertThat(reasons(id)).as("사건이 실어 온 사유가 이력에 남는다").last().isEqualTo("SHIPPING_STARTED");
    }

    @Test
    void 관리자_취소는_주체와_사유가_이력에_남는다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();

        ledger.fire(id, new CancelRequested(EventActor.ADMIN, "고객 전화 요청"));

        assertThat(history(id)).last().isEqualTo("2:PENDING_SYNC>CANCELING:ADMIN");
        assertThat(reasons(id)).last().isEqualTo("고객 전화 요청");
    }

    @Test
    void 결제_가능한_적이_없는_예약의_취소는_거절될_수_없다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();
        ledger.fire(id, new CancelRequested(EventActor.USER, null));

        assertThatThrownBy(() -> ledger.fire(id, new CancelRejected("SHIPPING_STARTED", null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row(id)).containsEntry("status", "CANCELING").containsEntry("payable_from", null);
    }

    @Test
    void 없는_예약에는_사건을_적용할_수_없다() {
        assertThatThrownBy(() -> ledger.fire(UUID.randomUUID(), new CancelRequested(EventActor.USER, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 활성_예약이_있으면_같은_모델은_UNIQUE_로_막힌다() {
        ledger.accept(draft(customerId), EventActor.USER, null);

        assertThatThrownBy(() -> ledger.accept(draft(customerId), EventActor.USER, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_preorder_active");
    }

    @Test
    void 취소가_끝나면_활성_표식이_사라져_같은_모델을_다시_신청할_수_있다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();
        ledger.fire(id, new CancelRequested(EventActor.USER, null));
        ledger.fire(id, new CancelCompleted());

        PreorderSnapshot again = ledger.accept(draft(customerId), EventActor.USER, null);

        assertThat(row(id)).containsEntry("status", "CANCELED").containsEntry("active_marker", null);
        assertThat(row(again.id())).containsEntry("active_marker", 1);
    }

    @Test
    void 같은_입장권으로는_두_번_접수할_수_없다() {
        String ticket = "a".repeat(64);
        ledger.accept(draft(customerId, ticket), EventActor.USER, null);

        assertThatThrownBy(() -> ledger.accept(draft(fixtures.customer(), ticket), EventActor.USER, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_preorder_admission");
    }

    @Test
    void 결제_시작은_상태와_이력을_그대로_두고_처음_시각만_남긴다() {
        UUID id = payable();
        Instant first = Instant.parse("2026-10-04T01:00:00Z");

        assertThat(ledger.fire(id, new PaymentStarted(first))).isEqualTo(new PreorderTransition(true, REGISTERED));
        ledger.fire(id, new PaymentStarted(first.plusSeconds(60)));

        assertThat(row(id)).containsEntry("status", "REGISTERED").containsEntry("event_sequence", 2L);
        assertThat(utc(row(id).get("payment_started_at"))).isEqualTo(first);
        assertThat(history(id)).hasSize(2);
    }

    @Test
    void 결제_확인은_예약_확정이고_다시_와도_그대로다() {
        UUID id = payable();
        Instant paidAt = Instant.parse("2026-10-04T01:00:00Z");

        assertThat(ledger.fire(id, new PaymentConfirmed(paidAt))).isEqualTo(new PreorderTransition(true, RESERVED));
        assertThat(ledger.fire(id, new PaymentConfirmed(paidAt.plusSeconds(60))))
                .isEqualTo(new PreorderTransition(false, RESERVED));

        assertThat(row(id)).containsEntry("status", "RESERVED");
        assertThat(utc(row(id).get("reserved_at"))).isEqualTo(paidAt);
        assertThat(history(id)).last().isEqualTo("3:REGISTERED>RESERVED:SYSTEM");
    }

    @Test
    void 확정된_예약의_취소가_거절되면_RESERVED_로_돌아간다() {
        UUID id = payable();
        ledger.fire(id, new PaymentConfirmed(Instant.parse("2026-10-04T01:00:00Z")));
        ledger.fire(id, new CancelRequested(EventActor.USER, null));

        assertThat(ledger.fire(id, new CancelRejected("SHIPPED", null))).isEqualTo(new PreorderTransition(true, RESERVED));
        assertThat(history(id)).last().isEqualTo("5:CANCELING>RESERVED:SYSTEM");
    }

    @Test
    void 결제_확인보다_거절이_먼저_와도_결제_시각이_실려_오면_RESERVED_로_돌아간다() {
        UUID id = payable();
        ledger.fire(id, new CancelRequested(EventActor.SYSTEM, null));
        Instant paidAt = Instant.parse("2026-10-04T01:00:00Z");

        assertThat(ledger.fire(id, new CancelRejected("PAID", paidAt))).isEqualTo(new PreorderTransition(true, RESERVED));
        assertThat(utc(row(id).get("reserved_at"))).isEqualTo(paidAt);
        assertThat(ledger.fire(id, new PaymentConfirmed(paidAt))).as("늦게 온 결제 확인은 무시")
                .isEqualTo(new PreorderTransition(false, RESERVED));
    }

    @Test
    void 취소_중_결제_확인은_시각만_남기고_거절되면_RESERVED_로_돌아간다() {
        UUID id = payable();
        ledger.fire(id, new CancelRequested(EventActor.USER, null));

        assertThat(ledger.fire(id, new PaymentConfirmed(Instant.parse("2026-10-04T01:00:00Z"))))
                .isEqualTo(new PreorderTransition(true, CANCELING));
        assertThat(history(id)).as("상태가 그대로라 이력은 남기지 않는다").hasSize(3);

        assertThat(ledger.fire(id, new CancelRejected("SHIPPED", null))).isEqualTo(new PreorderTransition(true, RESERVED));
    }

    @Test
    void 등록_전_예약의_결제_사건은_무시한다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();

        assertThat(ledger.fire(id, new PaymentConfirmed(Instant.now()))).isEqualTo(new PreorderTransition(false, PENDING_SYNC));
        assertThat(ledger.fire(id, new PaymentStarted(Instant.now()))).isEqualTo(new PreorderTransition(false, PENDING_SYNC));
        assertThat(row(id)).containsEntry("reserved_at", null).containsEntry("payment_started_at", null);
    }

    @Test
    void 관리자_전이는_사유가_없으면_거부하고_상태를_바꾸지_않는다() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();

        assertThatThrownBy(() -> ledger.fire(id, new CancelRequested(EventActor.ADMIN, " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(row(id)).containsEntry("status", "PENDING_SYNC");
    }

    @Test
    void 관리자_대신_접수는_입장권_없이_사유와_함께_남는다() {
        UUID id = ledger.accept(draft(customerId, null), EventActor.ADMIN, "전화 접수").id();

        assertThat(row(id)).containsEntry("admission_ticket_id", null);
        assertThat(history(id)).containsExactly("1:null>PENDING_SYNC:ADMIN");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 트랜잭션_밖에서는_상태를_바꿀_수_없다() {
        assertThatThrownBy(() -> ledger.fire(UUID.randomUUID(), new CancelRequested(EventActor.USER, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private NewPreorder draft(UUID customer) {
        return draft(customer, ShopFixtures.unique().replace("-", "") + "00000000000000000000000000000000");
    }

    private NewPreorder draft(UUID customer, String admissionTicketId) {
        long position = nextPosition.getAndIncrement();
        return new NewPreorder(ShopFixtures.unique(), customer, product.productId(), product.optionId(),
                product.firstBatchId(), position, admissionTicketId, ShopFixtures.unique(),
                "Nova 1", "블랙 / 256GB", new BigDecimal("1250000"));
    }

    /** 이력은 커밋할 때 flush 된다. JDBC 로 읽기 전에 밀어 넣는다. */
    private UUID payable() {
        UUID id = ledger.accept(draft(customerId), EventActor.USER, null).id();
        ledger.fire(id, new RegisterConfirmed("EXT-" + ShopFixtures.unique()));
        return id;
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private Instant utc(Object value) {
        return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private Map<String, Object> row(UUID id) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("""
                SELECT status, event_sequence, active_marker, payable_from, external_reference, admission_ticket_id,
                       payment_started_at, reserved_at
                  FROM preorders WHERE id = ?
                """, (Object) UuidBinary.toBytes(id));
    }

    /** "번호:from>to:actor" 목록. 번호 순. */
    private List<String> reasons(UUID id) {
        entityManager.flush();
        return jdbcTemplate.queryForList(
                "SELECT reason FROM preorder_events WHERE preorder_id = ? ORDER BY event_sequence", String.class,
                (Object) UuidBinary.toBytes(id));
    }

        private List<String> history(UUID id) {
        entityManager.flush();
        return jdbcTemplate.query("""
                SELECT event_sequence, from_status, to_status, actor
                  FROM preorder_events WHERE preorder_id = ? ORDER BY event_sequence
                """, (rs, n) -> rs.getLong(1) + ":" + rs.getString(2) + ">" + rs.getString(3) + ":" + rs.getString(4),
                (Object) UuidBinary.toBytes(id));
    }
}
