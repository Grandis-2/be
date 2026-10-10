package com.grandis.nova.order;

import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 장바구니 · 주문상품의 보증 칸과 수량 상한이 마이그레이션 그대로 막고 여는지. 엔티티를 거치지 않고 SQL 로 직접 친다 — 제약은 DB 가 지키고,
 * 다른 모듈 시험 픽스처도 같은 표에 기존 칸만으로 넣는다.
 * MySQL 의 CHECK 위반(3819)은 Spring 이 분류하지 않아 Uncategorized 로 온다. 제약 이름으로 단언한다.
 */
@OrderIntegrationTest
class CartAndOrderItemSchemaTest {

    @Autowired JdbcTemplate jdbcTemplate;

    OrderFixtures fixtures;
    UUID customerId;
    UUID optionId;
    UUID productId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        productId = product.productId();
        optionId = product.optionIds().getFirst();
    }

    @Test
    @DisplayName("장바구니는 같은 옵션이라도 보증 포함 · 미포함이 다른 줄이고, 같은 (회원, 옵션, 보증) 은 한 줄이다. 보증 칸을 안 쓰면 미포함")
    void cartLineIsUniquePerCustomerOptionAndWarranty() {
        cartLine(null, 1);
        cartLine(true, 2);

        assertThat(jdbcTemplate.queryForList("SELECT warranty_selected FROM cart_items WHERE customer_id = ? ORDER BY warranty_selected",
                Boolean.class, (Object) bytes(customerId))).containsExactly(false, true);
        assertThatThrownBy(() -> cartLine(true, 1))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_cart_customer_option_warranty");
        assertThatThrownBy(() -> cartLine(false, 1))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_cart_customer_option_warranty");
    }

    @Test
    @DisplayName("장바구니 한 줄 수량은 1~99 — 0 · 100 은 거절한다")
    void cartQuantityIsOneToNinetyNine() {
        cartLine(false, 1);
        cartLine(true, 99);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE cart_items SET quantity = 100 WHERE customer_id = ?", (Object) bytes(customerId)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_cart_quantity");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE cart_items SET quantity = 0 WHERE customer_id = ?", (Object) bytes(customerId)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_cart_quantity");
    }

    @Test
    @DisplayName("주문상품은 같은 옵션 한 줄에 보증 수량 · 보증가를 둔다 — 기존 칸만 쓰면 둘 다 0, 보증 수량은 0~수량, 보증가는 음수가 아니다")
    void orderItemCarriesWarrantyQuantityWithinItsQuantity() {
        UUID orderId = cartOrder();
        UUID itemId = TestIds.next();
        jdbcTemplate.update("""
                INSERT INTO order_items (id, order_id, product_id, option_id, quantity, unit_price_snapshot,
                                         product_title_snapshot, option_title_snapshot)
                VALUES (?, ?, ?, ?, 3, 1000, 't', 'o')""", bytes(itemId), bytes(orderId), bytes(productId), bytes(optionId));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT warranty_quantity, warranty_price_snapshot FROM order_items WHERE id = ?", (Object) bytes(itemId));
        assertThat(row.get("warranty_quantity")).isEqualTo(0);
        assertThat((BigDecimal) row.get("warranty_price_snapshot")).isEqualByComparingTo("0");

        jdbcTemplate.update("UPDATE order_items SET warranty_quantity = 3, warranty_price_snapshot = 199000 WHERE id = ?", (Object) bytes(itemId));
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE order_items SET warranty_quantity = 4 WHERE id = ?", (Object) bytes(itemId)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_order_item_warranty_quantity");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE order_items SET warranty_quantity = -1 WHERE id = ?", (Object) bytes(itemId)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_order_item_warranty_quantity");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE order_items SET warranty_price_snapshot = -1 WHERE id = ?", (Object) bytes(itemId)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_order_item_warranty_price");
    }

    @Test
    @DisplayName("같은 주문 · 같은 옵션은 주문상품 한 줄이다(uq_order_item_option 그대로) — 보증 포함 · 미포함을 두 줄로 나누지 않는다")
    void orderItemStaysOnePerOrderAndOption() {
        UUID orderId = cartOrder();
        String insert = """
                INSERT INTO order_items (id, order_id, product_id, option_id, quantity, warranty_quantity, unit_price_snapshot,
                                         warranty_price_snapshot, product_title_snapshot, option_title_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, 1000, 199000, 't', 'o')""";
        jdbcTemplate.update(insert, bytes(TestIds.next()), bytes(orderId), bytes(productId), bytes(optionId), 2, 2);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, bytes(TestIds.next()), bytes(orderId), bytes(productId), bytes(optionId), 1, 0))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_order_item_option");
    }

    private void cartLine(Boolean warranty, int quantity) {
        if (warranty == null) {
            jdbcTemplate.update("""
                    INSERT INTO cart_items (id, customer_id, option_id, quantity, created_at, updated_at)
                    VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                    bytes(TestIds.next()), bytes(customerId), bytes(optionId), quantity);
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(TestIds.next()), bytes(customerId), bytes(optionId), warranty, quantity);
    }

    /**
     * 결제 대기 장바구니 주문 하나 — 일반 주문은 결제 기한이 있어야 한다(ck_order_due). 앱이 만들 수 있는 행이어야 한다: 이력 번호는 1(첫 이력)이다 —
     * DB 기본값 0 이면 시험 DB 를 함께 쓰는 다른 시험(관리자 주문 목록)이 이 행을 읽다 도메인 검사에 걸린다.
     */
    private UUID cartOrder() {
        UUID orderId = TestIds.next();
        jdbcTemplate.update("""
                INSERT INTO orders (id, order_token, customer_id, source, status, total_amount, payment_due_at,
                                    ship_to_name, ship_to_phone, ship_to_postal_code, ship_to_line1, event_sequence, created_at, updated_at)
                VALUES (?, ?, ?, 'CART', 'AWAITING_PAYMENT', 0, UTC_TIMESTAMP(6), 'a', '1', '1', 'x', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(orderId), OrderFixtures.unique(), bytes(customerId));
        return orderId;
    }
}
