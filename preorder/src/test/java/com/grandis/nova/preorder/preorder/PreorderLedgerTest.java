package com.grandis.nova.preorder.preorder;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.grandis.nova.preorder.preorder.PreorderStatus.CANCELING;
import static com.grandis.nova.preorder.preorder.PreorderStatus.PAYABLE;
import static com.grandis.nova.preorder.preorder.PreorderStatus.PENDING_SYNC;
import static com.grandis.nova.preorder.preorder.PreorderTrigger.CANCEL_COMPLETED;
import static com.grandis.nova.preorder.preorder.PreorderTrigger.CANCEL_REJECTED;
import static com.grandis.nova.preorder.preorder.PreorderTrigger.CANCEL_REQUESTED;
import static com.grandis.nova.preorder.preorder.PreorderTrigger.REGISTER_CONFIRMED;
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
    Long customerId;
    final AtomicLong nextPosition = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        product = fixtures.openPreorderProduct();
        customerId = fixtures.customer();
    }

    @Test
    void 접수하면_PENDING_SYNC_와_첫_이력이_남고_활성으로_표시된다() {
        Preorder preorder = ledger.accept(draft(customerId), EventActor.USER, null);

        assertThat(preorder.getId()).isNotNull();
        assertThat(row(preorder.getId()))
                .containsEntry("status", "PENDING_SYNC")
                .containsEntry("event_sequence", 1L)
                .containsEntry("active_marker", 1);
        assertThat(history(preorder.getId())).containsExactly("1:null>PENDING_SYNC:USER");
    }

    @Test
    void 사건이_적용되면_이력_번호가_하나_오르고_이력이_남는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();

        PreorderTransition result = ledger.fire(id, CANCEL_REQUESTED, EventActor.USER, null);

        assertThat(result).isEqualTo(new PreorderTransition(true, CANCELING));
        assertThat(row(id)).containsEntry("status", "CANCELING").containsEntry("event_sequence", 2L);
        assertThat(history(id)).containsExactly("1:null>PENDING_SYNC:USER", "2:PENDING_SYNC>CANCELING:USER");
    }

    @Test
    void 지금_상태에서_의미_없는_사건이면_아무것도_바꾸지_않는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();

        PreorderTransition result = ledger.fire(id, CANCEL_COMPLETED, EventActor.SYSTEM, null);

        assertThat(result).isEqualTo(new PreorderTransition(false, PENDING_SYNC));
        assertThat(row(id)).containsEntry("status", "PENDING_SYNC").containsEntry("event_sequence", 1L);
        assertThat(history(id)).hasSize(1);
    }

    @Test
    void 취소를_두_번_요청해도_한_번만_반영된다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();
        ledger.fire(id, CANCEL_REQUESTED, EventActor.USER, null);

        PreorderTransition again = ledger.fire(id, CANCEL_REQUESTED, EventActor.USER, null);

        assertThat(again).isEqualTo(new PreorderTransition(false, CANCELING));
        assertThat(history(id)).hasSize(2);
    }

    @Test
    void 등록_확인은_전용_메서드로만_반영한다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();

        assertThatThrownBy(() -> ledger.fire(id, REGISTER_CONFIRMED, EventActor.SYSTEM, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmRegister");
        assertThat(row(id)).containsEntry("status", "PENDING_SYNC");
    }

    @Test
    void 등록_확인은_한_번만_되고_결제_기한_기준_시각을_찍는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();
        String externalReference = "EXT-" + ShopFixtures.unique();

        assertThat(ledger.confirmRegister(id, externalReference)).isEqualTo(new PreorderTransition(true, PAYABLE));
        Object payableFrom = row(id).get("payable_from");
        assertThat(ledger.confirmRegister(id, "EXT-" + ShopFixtures.unique()))
                .isEqualTo(new PreorderTransition(false, PAYABLE));

        assertThat(payableFrom).isNotNull();
        assertThat(row(id))
                .containsEntry("status", "PAYABLE")
                .containsEntry("external_reference", externalReference)
                .containsEntry("payable_from", payableFrom);
        assertThat(history(id)).containsExactly("1:null>PENDING_SYNC:USER", "2:PENDING_SYNC>PAYABLE:SYSTEM");
    }

    @Test
    void 취소_중인_예약에_늦게_온_등록_확인은_반영하지_않는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();
        ledger.fire(id, CANCEL_REQUESTED, EventActor.USER, null);

        assertThat(ledger.confirmRegister(id, "EXT-LATE")).isEqualTo(new PreorderTransition(false, CANCELING));

        assertThat(row(id)).containsEntry("status", "CANCELING").containsEntry("payable_from", null);
    }

    @Test
    void PAYABLE_에서_시작한_취소가_거절되면_PAYABLE_로_되돌린다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();
        ledger.confirmRegister(id, "EXT-" + ShopFixtures.unique());
        ledger.fire(id, CANCEL_REQUESTED, EventActor.USER, null);

        PreorderTransition result = ledger.fire(id, CANCEL_REJECTED, EventActor.SYSTEM, "SHIPPING_STARTED");

        assertThat(result).isEqualTo(new PreorderTransition(true, PAYABLE));
        assertThat(history(id)).last().isEqualTo("4:CANCELING>PAYABLE:SYSTEM");
    }

    @Test
    void 결제_가능한_적이_없는_예약의_취소는_거절될_수_없다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();
        ledger.fire(id, CANCEL_REQUESTED, EventActor.USER, null);

        assertThatThrownBy(() -> ledger.fire(id, CANCEL_REJECTED, EventActor.SYSTEM, "SHIPPING_STARTED"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row(id)).containsEntry("status", "CANCELING").containsEntry("payable_from", null);
    }

    @Test
    void 없는_예약에는_사건을_적용할_수_없다() {
        assertThatThrownBy(() -> ledger.fire(Long.MAX_VALUE, CANCEL_REQUESTED, EventActor.USER, null))
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
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();
        ledger.fire(id, CANCEL_REQUESTED, EventActor.USER, null);
        ledger.fire(id, CANCEL_COMPLETED, EventActor.SYSTEM, null);

        Preorder again = ledger.accept(draft(customerId), EventActor.USER, null);

        assertThat(row(id)).containsEntry("status", "CANCELED").containsEntry("active_marker", null);
        assertThat(row(again.getId())).containsEntry("active_marker", 1);
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
    void 관리자_전이는_사유가_없으면_거부하고_상태를_바꾸지_않는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();

        assertThatThrownBy(() -> ledger.fire(id, CANCEL_REQUESTED, EventActor.ADMIN, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(row(id)).containsEntry("status", "PENDING_SYNC");
    }

    @Test
    void 관리자_대신_접수는_입장권_없이_사유와_함께_남는다() {
        Long id = ledger.accept(draft(customerId, null), EventActor.ADMIN, "전화 접수").getId();

        assertThat(row(id)).containsEntry("admission_ticket_id", null);
        assertThat(history(id)).containsExactly("1:null>PENDING_SYNC:ADMIN");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 트랜잭션_밖에서는_상태를_바꿀_수_없다() {
        assertThatThrownBy(() -> ledger.fire(1L, CANCEL_REQUESTED, EventActor.USER, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private NewPreorder draft(Long customer) {
        return draft(customer, ShopFixtures.unique().replace("-", "") + "00000000000000000000000000000000");
    }

    private NewPreorder draft(Long customer, String admissionTicketId) {
        long position = nextPosition.getAndIncrement();
        return new NewPreorder(ShopFixtures.unique(), customer, product.productId(), product.optionId(),
                product.firstBatchId(), position, admissionTicketId, ShopFixtures.unique(),
                "Nova 1", "블랙 / 256GB", new BigDecimal("1250000"));
    }

    /** 이력은 커밋할 때 flush 된다. JDBC 로 읽기 전에 밀어 넣는다. */
    private Map<String, Object> row(Long id) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("""
                SELECT status, event_sequence, active_marker, payable_from, external_reference, admission_ticket_id
                  FROM preorders WHERE id = ?
                """, id);
    }

    /** "번호:from>to:actor" 목록. 번호 순. */
    private List<String> history(Long id) {
        entityManager.flush();
        return jdbcTemplate.query("""
                SELECT event_sequence, from_status, to_status, actor
                  FROM preorder_events WHERE preorder_id = ? ORDER BY event_sequence
                """, (rs, n) -> rs.getLong(1) + ":" + rs.getString(2) + ">" + rs.getString(3) + ":" + rs.getString(4),
                id);
    }
}
