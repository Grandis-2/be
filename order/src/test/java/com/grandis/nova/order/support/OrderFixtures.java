package com.grandis.nova.order.support;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 테스트 데이터. 주문 · 재고가 참조하는 다른 모듈 소유 행(회원 · 카테고리 · 상품 · 옵션 · 배송 차수 · 예약)을 SQL 로 바로 넣는다.
 * 운영 코드의 모듈 경계와 무관하다 — 테스트 픽스처만 남의 테이블에 쓴다.
 *
 * 매번 새 행을 만들고 지우지 않는다. id · 유일 칸은 UUID 로 채워 테스트끼리 겹치지 않으므로
 * 커밋하는 동시성 테스트와 롤백하는 테스트가 같은 컨테이너를 순서 상관없이 쓸 수 있다.
 */
public class OrderFixtures {

    public static final BigDecimal UNIT_PRICE = new BigDecimal("1250000");
    public static final String PRODUCT_TITLE = "Nova 1";
    public static final String OPTION_TITLE = "블랙 / 256GB";
    public static final PlaceOrderCommand.Address ADDRESS =
            new PlaceOrderCommand.Address("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null);

    private final JdbcTemplate jdbcTemplate;

    public OrderFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID customer() {
        return insert("""
                INSERT INTO customers (id, kakao_id, display_name, created_at, updated_at)
                VALUES (?, ?, '테스트 회원', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, unique());
    }

    /** 사전예약 상품 하나(옵션 하나, 차수 하나). */
    public PreorderProduct preorderProduct() {
        UUID productId = product("PREORDER");
        UUID optionId = option(productId);
        LocalDate shipStart = LocalDate.of(2026, 11, 1);
        UUID batchId = insert("""
                INSERT INTO shipment_batches (id, product_id, batch_number, position_from, position_to,
                                              estimated_ship_start, estimated_ship_end, created_at)
                VALUES (?, ?, 1, 1, NULL, ?, ?, UTC_TIMESTAMP(6))
                """, bytes(productId), shipStart, shipStart.plusDays(6));
        return new PreorderProduct(productId, optionId, batchId);
    }

    /**
     * 결제 가능한 예약. 주문은 이 상태의 예약에서만 만들어진다. 공개 UUID 는 {@link #preorderToken(UUID)} 이다.
     *
     * @param queuePosition 같은 상품 안에서 겹치지 않아야 한다(uq_preorder_position)
     */
    public UUID payablePreorder(UUID customerId, PreorderProduct product, long queuePosition) {
        UUID preorderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO preorders (id, preorder_token, customer_id, product_id, option_id, shipment_batch_id,
                                       queue_position, idempotency_key, product_title_snapshot,
                                       option_title_snapshot, unit_price_snapshot, status, payable_from,
                                       event_sequence, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PAYABLE', UTC_TIMESTAMP(6), 2,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(preorderId), preorderToken(preorderId), bytes(customerId), bytes(product.productId()),
                bytes(product.optionId()), bytes(product.batchId()), queuePosition, unique(), PRODUCT_TITLE,
                OPTION_TITLE, UNIT_PRICE);
        return preorderId;
    }

    /**
     * 픽스처 예약의 공개 UUID. id 로 정해 두어, 예약 id 만 받는 명령(static)도 DB 와 같은 짝(preorder_id ↔ preorder_token)을
     * 싣는다. id 가 다르면 UUID 도 다르다.
     */
    public static String preorderToken(UUID preorderId) {
        return UUID.nameUUIDFromBytes(UuidBinary.toBytes(preorderId)).toString();
    }

    /** 그 예약으로 옵션 하나 · 수량 1 을 주문하는 명령. */
    public static PlaceOrderCommand preorderCommand(UUID customerId, UUID preorderId, PreorderProduct product) {
        return preorderCommand(customerId, preorderId, product, product.optionId(), 1);
    }

    public static PlaceOrderCommand preorderCommand(UUID customerId, UUID preorderId, PreorderProduct product,
                                                    UUID optionId, int quantity) {
        return new PlaceOrderCommand(customerId, OrderSource.PREORDER, preorderId, preorderToken(preorderId), ADDRESS,
                List.of(new PlaceOrderCommand.Line(product.productId(), optionId, quantity, UNIT_PRICE,
                        PRODUCT_TITLE, OPTION_TITLE)));
    }

    /** 일반 판매 상품 하나와 옵션 optionCount 개. 재고 행은 만들지 않는다. option_id 오름차순. */
    public StockProduct inStockProduct(int optionCount) {
        UUID productId = product("IN_STOCK");
        List<UUID> optionIds = new ArrayList<>();
        for (int i = 0; i < optionCount; i++) {
            optionIds.add(option(productId));
        }
        optionIds.sort(UuidBinary.BYTE_ORDER);
        return new StockProduct(productId, List.copyOf(optionIds));
    }

    /** 재고 행을 바로 넣는다. 확보 · 판매는 아직 그 코드가 없어 이렇게 심는다. */
    public void stock(UUID optionId, int total, int reserved, int sold) {
        jdbcTemplate.update("""
                INSERT INTO option_inventories (option_id, stock_total, stock_reserved, stock_sold, updated_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, bytes(optionId), total, reserved, sold);
    }

    /** 원장이 아직 만들지 않는 전이(배송 등)를 거친 주문을 흉내 낸다. */
    /** 승인 중(AUTHORIZING)으로 바꾸면 임의의 결제창 번호를 함께 적는다(ck_order_authorizing_attempt). */
    public void forceStatus(UUID orderId, String status) {
        forceStatus(orderId, status, "AUTHORIZING".equals(status) ? unique() : null);
    }

    public void forceAuthorizing(UUID orderId, String providerOrderId) {
        forceStatus(orderId, "AUTHORIZING", providerOrderId);
    }

    private void forceStatus(UUID orderId, String status, String authorizingProviderOrderId) {
        jdbcTemplate.update("UPDATE orders SET status = ?, authorizing_provider_order_id = ? WHERE id = ?",
                status, authorizingProviderOrderId, bytes(orderId));
    }

    public static String unique() {
        return UUID.randomUUID().toString();
    }

    private UUID product(String saleMode) {
        UUID categoryId = insert("""
                INSERT INTO categories (id, name, created_at, updated_at)
                VALUES (?, '스마트폰', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        return insert("""
                INSERT INTO products (id, category_id, sale_mode, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(categoryId), saleMode, PRODUCT_TITLE);
    }

    private UUID option(UUID productId) {
        return insert("""
                INSERT INTO product_options (id, product_id, sku, title, price, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(productId), unique(), OPTION_TITLE, UNIT_PRICE);
    }

    /** 첫 칸이 id 인 INSERT 에 새 id 를 붙여 실행하고 그 id 를 돌려준다. */
    private UUID insert(String sql, Object... args) {
        UUID id = UUID.randomUUID();
        Object[] withId = new Object[args.length + 1];
        withId[0] = bytes(id);
        System.arraycopy(args, 0, withId, 1, args.length);
        jdbcTemplate.update(sql, withId);
        return id;
    }

    public static byte[] bytes(UUID id) {
        return UuidBinary.toBytes(id);
    }

    public record PreorderProduct(UUID productId, UUID optionId, UUID batchId) {
    }

    /** @param optionIds option_id 오름차순 */
    public record StockProduct(UUID productId, List<UUID> optionIds) {
    }
}
