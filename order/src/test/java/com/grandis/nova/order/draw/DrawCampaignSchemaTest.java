package com.grandis.nova.order.draw;

import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** draw_campaigns 제약 — 앱을 거치지 않은 쓰기에도 DB 가 지킨다. 각 시험은 롤백한다. */
@OrderIntegrationTest
@Transactional
class DrawCampaignSchemaTest {

    @Autowired JdbcTemplate jdbcTemplate;

    UUID product;
    UUID option;

    @BeforeEach
    void setUp() {
        OrderFixtures.StockProduct stocked = new OrderFixtures(jdbcTemplate).inStockProduct(1);
        product = stocked.productId();
        option = stocked.optionIds().getFirst();
    }

    @Test
    void validRowIsAccepted() {
        assertThatCode(() -> insert(product, option, "100", 1, "2026-10-11", "2026-10-12")).doesNotThrowAnyException();
    }

    @Test
    void feeWinnersPeriodAndOptionAreGuarded() {
        assertThatThrownBy(() -> insert(product, option, "0", 1, "2026-10-11", "2026-10-12"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_draw_campaign_entry_fee");
        assertThatThrownBy(() -> insert(product, option, "100", 0, "2026-10-11", "2026-10-12"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_draw_campaign_winner_count");
        assertThatThrownBy(() -> insert(product, option, "100", 1, "2026-10-12", "2026-10-12"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_draw_campaign_period");
        assertThatThrownBy(() -> insert(UUID.randomUUID(), option, "100", 1, "2026-10-11", "2026-10-12"))
                .as("상품과 옵션의 짝").isInstanceOf(DataAccessException.class).hasMessageContaining("fk_draw_campaign_option");
    }

    private void insert(UUID productId, UUID optionId, String fee, int winners, String opens, String closes) {
        jdbcTemplate.update("""
                INSERT INTO draw_campaigns (id, product_id, option_id, title, product_title_snapshot, option_title_snapshot, entry_fee,
                                            winner_count, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, 't', 'p', 'o', ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(UUID.randomUUID()), bytes(productId), bytes(optionId), new java.math.BigDecimal(fee), winners, opens, closes);
    }
}
