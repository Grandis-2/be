package com.grandis.nova.order.support;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 테스트 데이터. 재고 · 장바구니가 읽거나 외래 키로 가리키는 다른 모듈 소유 행(회원 · 카테고리 · 상품 · 옵션)을 SQL 로 바로 넣는다.
 * 운영 코드의 모듈 경계와 무관하다 — 테스트 픽스처만 남의 테이블에 쓴다. 예약 행은 넣지 않는다 — order 는 예약을 preorder API 로만 본다.
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

    /** 사전예약 상품 하나(옵션 하나). */
    public PreorderProduct preorderProduct() {
        UUID productId = product("PREORDER");
        return new PreorderProduct(productId, option(productId));
    }

    /** 주문이 가리킬 예약의 id. 예약 행은 만들지 않는다. 공개 UUID 는 {@link #preorderToken(UUID)} 이다. */
    public static UUID preorderId() {
        return TestIds.next();
    }

    /**
     * 픽스처 예약의 공개 UUID. id 로 정해 두어, 예약 id 만 받는 곳도 같은 짝(preorder_id ↔ preorder_token)을 만든다.
     * id 가 다르면 UUID 도 다르다.
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
        return new PlaceOrderCommand(customerId, OrderSource.PREORDER, preorderId, preorderToken(preorderId), null, ADDRESS,
                List.of(new PlaceOrderCommand.Line(product.productId(), optionId, quantity, UNIT_PRICE, 0, BigDecimal.ZERO,
                        PRODUCT_TITLE, null, OPTION_TITLE, null)));
    }

    /** 일반 판매 상품 하나와 옵션 optionCount 개. 재고 행은 만들지 않는다. 만든 순서 = option_id 오름차순. */
    public StockProduct inStockProduct(int optionCount) {
        UUID productId = product("IN_STOCK");
        List<UUID> optionIds = new ArrayList<>();
        for (int i = 0; i < optionCount; i++) {
            optionIds.add(option(productId));
        }
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
                INSERT INTO products (id, category_id, sale_mode, title, brand, model_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'APPLE', 'A0001', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
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
        UUID id = TestIds.next();
        Object[] withId = new Object[args.length + 1];
        withId[0] = bytes(id);
        System.arraycopy(args, 0, withId, 1, args.length);
        jdbcTemplate.update(sql, withId);
        return id;
    }

    public static byte[] bytes(UUID id) {
        return UuidBinary.toBytes(id);
    }

    public record PreorderProduct(UUID productId, UUID optionId) {
    }

    /** @param optionIds option_id 오름차순 */
    public record StockProduct(UUID productId, List<UUID> optionIds) {
    }
}
