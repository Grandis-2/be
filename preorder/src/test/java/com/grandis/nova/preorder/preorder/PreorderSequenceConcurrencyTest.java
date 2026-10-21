package com.grandis.nova.preorder.preorder;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.preorder.preorder.PreorderFact.CancelRejected;
import com.grandis.nova.preorder.preorder.PreorderFact.CancelRequested;
import com.grandis.nova.preorder.preorder.PreorderFact.PaymentConfirmed;
import com.grandis.nova.preorder.preorder.PreorderFact.RegisterConfirmed;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 이력 순번은 잠글 때 읽은 순번 + 1 이다 — 같은 예약에 결과 · 취소가 겹쳐도 빈틈 · 겹침 없이 이어지고 예약 행의 순번과 맞는다. */
@PreorderIntegrationTest
class PreorderSequenceConcurrencyTest {

    static final int PREORDERS = 5;

    @Autowired
    PreorderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 결제_확인과_취소가_겹쳐도_이력_순번은_빈틈_겹침_없이_이어진다() throws Exception {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        PreorderProduct product = fixtures.openPreorderProduct();
        List<UUID> ids = LongStream.rangeClosed(1, PREORDERS).mapToObj(position -> transactionTemplate.execute(status -> {
            UUID id = ledger.accept(draft(fixtures.customer(), product, position), EventActor.USER, null).id();
            ledger.fire(id, new RegisterConfirmed("EXT-" + ShopFixtures.unique()));
            return id;
        })).toList();
        Instant paidAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        List<Outcome<PreorderTransition>> outcomes = Concurrently.run(PREORDERS * 2, i -> () ->
                transactionTemplate.execute(status -> ledger.fire(ids.get(i / 2), i % 2 == 0
                        ? new CancelRequested(EventActor.USER, null) : new PaymentConfirmed(paidAt))));
        ids.forEach(id -> transactionTemplate.executeWithoutResult(status ->
                ledger.fire(id, new CancelRejected("SHIPPED", null))));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        for (UUID id : ids) {
            List<Long> sequences = jdbcTemplate.queryForList(
                    "SELECT event_sequence FROM preorder_events WHERE preorder_id = ? ORDER BY event_sequence",
                    Long.class, (Object) UuidBinary.toBytes(id));
            assertThat(sequences).as("1 부터 빈틈 없이").isEqualTo(LongStream.rangeClosed(1, sequences.size()).boxed().toList());
            assertThat(jdbcTemplate.queryForObject("SELECT event_sequence FROM preorders WHERE id = ?", Long.class,
                    (Object) UuidBinary.toBytes(id))).isEqualTo(sequences.getLast());
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM preorders WHERE id = ?", String.class,
                    (Object) UuidBinary.toBytes(id))).as("결제가 남아 확정으로 돌아간다").isEqualTo("RESERVED");
        }
    }

    private static NewPreorder draft(UUID customer, PreorderProduct product, long position) {
        return new NewPreorder(ShopFixtures.unique(), customer, product.productId(), product.optionId(),
                product.firstBatchId(), position, ShopFixtures.unique().replace("-", "") + "00000000000000000000000000000000",
                ShopFixtures.unique(), "Nova 1", "블랙 / 256GB", new BigDecimal("1250000"));
    }
}
