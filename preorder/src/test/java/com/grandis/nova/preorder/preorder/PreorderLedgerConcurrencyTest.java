package com.grandis.nova.preorder.preorder;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 회원 · 같은 모델의 접수가 별도 트랜잭션에서 동시에 들어올 때 UNIQUE(uq_preorder_active)가 하나만 남기는지.
 *
 * 순번 발급 경합은 PreorderCampaignRepositoryTest 가, 같은 접수 키 재전송 · 실패 시 순번 롤백은
 * 접수 유스케이스(NV-34)가 검증한다. 여기서는 각 요청에 서로 다른 순번 · 접수 키를 주고 활성 예약 제약만 본다.
 */
@PreorderIntegrationTest
class PreorderLedgerConcurrencyTest {

    @Autowired
    PreorderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 같은_모델을_동시에_접수하면_하나만_커밋되고_나머지는_UNIQUE_로_실패한다() throws Exception {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        PreorderProduct product = fixtures.openPreorderProduct();
        UUID customerId = fixtures.customer();
        int requests = 5;

        List<Outcome<UUID>> outcomes = Concurrently.run(requests, i -> () -> transactionTemplate.execute(status ->
                ledger.accept(draft(product, customerId, i + 1), EventActor.USER, null).id()));

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .hasSize(requests - 1)
                .allSatisfy(o -> assertThat(o.error())
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("uq_preorder_active"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM preorders WHERE customer_id = ? AND product_id = ? AND active_marker = 1
                """, Integer.class, UuidBinary.toBytes(customerId), UuidBinary.toBytes(product.productId())))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM preorder_events e JOIN preorders p ON p.id = e.preorder_id
                 WHERE p.customer_id = ? AND p.product_id = ?
                """, Integer.class, UuidBinary.toBytes(customerId), UuidBinary.toBytes(product.productId())))
                .isEqualTo(1);
    }

    private static NewPreorder draft(PreorderProduct product, UUID customerId, long position) {
        return new NewPreorder(ShopFixtures.unique(), customerId, product.productId(), product.optionId(),
                product.firstBatchId(), position, null, ShopFixtures.unique(),
                "Nova 1", "블랙 / 256GB", new BigDecimal("1250000"));
    }
}
