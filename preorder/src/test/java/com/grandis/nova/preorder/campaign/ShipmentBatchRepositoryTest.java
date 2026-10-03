package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.grandis.nova.preorder.support.ShopFixtures.FIRST_BATCH_LAST_POSITION;
import static org.assertj.core.api.Assertions.assertThat;

@PreorderIntegrationTest
class ShipmentBatchRepositoryTest {

    @Autowired
    ShipmentBatchRepository batches;

    @Autowired
    JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 순번으로_차수를_찾는다_경계_포함() {
        PreorderProduct product = fixtures.openPreorderProduct();

        assertThat(batches.findCovering(product.productId(), 1)).get()
                .extracting(ShipmentBatch::getId).isEqualTo(product.firstBatchId());
        assertThat(batches.findCovering(product.productId(), FIRST_BATCH_LAST_POSITION)).get()
                .extracting(ShipmentBatch::getId).isEqualTo(product.firstBatchId());
        assertThat(batches.findCovering(product.productId(), FIRST_BATCH_LAST_POSITION + 1)).get()
                .extracting(ShipmentBatch::getId).isEqualTo(product.lastBatchId());
    }

    @Test
    void 상한_없는_마지막_차수가_남은_순번을_모두_받는다() {
        PreorderProduct product = fixtures.openPreorderProduct();

        ShipmentBatch last = batches.findCovering(product.productId(), 1_000_000).orElseThrow();

        assertThat(last.getId()).isEqualTo(product.lastBatchId());
        assertThat(last.getPositionTo()).isNull();
        assertThat(last.covers(Long.MAX_VALUE)).isTrue();
    }

    @Test
    void 차수가_없는_상품이면_비어_있다() {
        Long productId = fixtures.product("PREORDER", "ACTIVE");

        assertThat(batches.findCovering(productId, 1)).isEmpty();
    }

    @Test
    void 차수는_번호_순으로_조회된다() {
        PreorderProduct product = fixtures.openPreorderProduct();

        assertThat(batches.findByProductIdOrderByBatchNumber(product.productId()))
                .extracting(ShipmentBatch::getBatchNumber).containsExactly(1, 2);
    }
}
