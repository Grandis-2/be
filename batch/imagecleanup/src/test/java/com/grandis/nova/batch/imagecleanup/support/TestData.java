package com.grandis.nova.batch.imagecleanup.support;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 사진 정리에 필요한 최소 업무 행을 넣는다(시험 전용 — 운영 사진 정리는 images 삭제 · 삭제 대기 말고는 업무 표에 쓰지 않는다).
 * 드로우 회차 · 주문 줄은 사진 주소를 복사해 두는 쪽이라, 정리가 그 사진을 지우지 않는지 볼 때 쓴다.
 */
public final class TestData {

    public static final String SKU = "TEST-SKU-256";

    private final JdbcClient jdbc;
    private final Map<String, UUID[]> products = new HashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public TestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** catalog 가 올린 사진 행. 키는 서버가 만드는 모양({prefix}/{id}.jpg)이다 */
    public ImageRow image(String prefix, String status, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        String key = prefix + "/" + id + ".jpg";
        jdbc.sql("""
                        INSERT INTO images (id, object_key, status, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), :key, :status, :at, :at)
                        """)
                .param("id", id.toString()).param("key", key).param("status", status).param("at", utc(updatedAt))
                .update();
        return new ImageRow(id, key);
    }

    /** 드로우 회차. 증정품은 이름별 상품의 옵션이고, imageUrl 은 회차를 만들 때 복사해 둔 증정품 사진 주소다 */
    public void draw(String productName, String imageUrl) {
        UUID[] product = productRow(productName);
        LocalDateTime at = utc(Instant.now());
        jdbc.sql("""
                        INSERT INTO draw_campaigns (id, idempotency_key, product_id, option_id, title, product_title_snapshot,
                                                    option_title_snapshot, image_url_snapshot, entry_fee, winner_count,
                                                    opens_at, closes_at, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), :key, UUID_TO_BIN(:product), UUID_TO_BIN(:option), '증정 회차', :name,
                                '실버 / 256GB', :image, 1000, 1, :at, :closes, :at, :at)
                        """)
                .param("id", UUID.randomUUID().toString()).param("key", "draw-" + UUID.randomUUID())
                .param("product", product[0].toString()).param("option", product[1].toString()).param("name", productName)
                .param("image", imageUrl).param("at", at).param("closes", at.plusDays(1))
                .update();
    }

    /** 장바구니 주문 한 줄. 상품은 이름별 상품의 옵션이고, imageUrl 은 주문 때 복사해 둔 옵션 사진 주소다 */
    public void orderItem(String productName, String imageUrl) {
        UUID[] product = productRow(productName);
        int n = sequence.incrementAndGet();
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        LocalDateTime at = utc(Instant.now());
        jdbc.sql("""
                        INSERT INTO customers (id, kakao_id, display_name, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), :kakao, :name, :at, :at)
                        """)
                .param("id", customerId.toString()).param("kakao", "test-order-" + n).param("name", "주문회원" + n).param("at", at)
                .update();
        jdbc.sql("""
                        INSERT INTO orders (id, customer_id, source, status, total_amount, payment_due_at, ship_to_name,
                                            ship_to_phone, ship_to_postal_code, ship_to_line1, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:customer), 'CART', 'AWAITING_CONFIRMATION', 1000, :due,
                                '받는 사람', '010-0000-0000', '00000', '주소', :at, :at)
                        """)
                .param("id", orderId.toString()).param("customer", customerId.toString()).param("due", at.plusMinutes(30))
                .param("at", at)
                .update();
        jdbc.sql("""
                        INSERT INTO order_items (id, order_id, product_id, option_id, quantity, unit_price_snapshot,
                                                 product_title_snapshot, option_title_snapshot, image_url_snapshot)
                        VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:order), UUID_TO_BIN(:product), UUID_TO_BIN(:option), 1, 1000,
                                :name, '실버 / 256GB', :image)
                        """)
                .param("id", UUID.randomUUID().toString()).param("order", orderId.toString())
                .param("product", product[0].toString()).param("option", product[1].toString()).param("name", productName)
                .param("image", imageUrl)
                .update();
    }

    /** 지난 실행이 남긴 삭제 대기 */
    public void pendingDeletion(String objectKey, Instant at) {
        jdbc.sql("INSERT INTO image_deletions (object_key, created_at) VALUES (:key, :at)")
                .param("key", objectKey).param("at", utc(at))
                .update();
    }

    /** 시험이 넣은 행을 지운다. 외래 키 순서대로 */
    public void clear() {
        for (String table : new String[]{"image_deletions", "images", "draw_campaigns", "order_items", "orders",
                "shipment_batches", "customers", "product_options", "products", "categories"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        products.clear();
    }

    /** 이름별 상품 하나(상품 · 옵션 · 배송 차수). 같은 이름이면 같은 상품이다 */
    private UUID[] productRow(String name) {
        return products.computeIfAbsent(name, this::createProduct);
    }

    private UUID[] createProduct(String name) {
        UUID categoryId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        LocalDateTime at = utc(Instant.now());
        jdbc.sql("INSERT INTO categories (id, name, created_at, updated_at) VALUES (UUID_TO_BIN(:id), :name, :at, :at)")
                .param("id", categoryId.toString()).param("name", "분류-" + name).param("at", at).update();
        jdbc.sql("""
                        INSERT INTO products (id, category_id, sale_mode, title, brand, model_name, status, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:category), 'PREORDER', :title, 'APPLE', 'A0000', 'ACTIVE', :at, :at)
                        """)
                .param("id", productId.toString()).param("category", categoryId.toString()).param("title", name)
                .param("at", at).update();
        jdbc.sql("""
                        INSERT INTO product_options (id, product_id, sku, title, price, status, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:product), :sku, '실버 / 256GB', 1000, 'ACTIVE', :at, :at)
                        """)
                .param("id", optionId.toString()).param("product", productId.toString()).param("sku", SKU + "-" + name)
                .param("at", at).update();
        jdbc.sql("""
                        INSERT INTO shipment_batches (id, product_id, batch_number, position_from, estimated_ship_start,
                                                      estimated_ship_end, created_at)
                        VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:product), 1, 1, '2026-12-01', '2026-12-05', :at)
                        """)
                .param("id", batchId.toString()).param("product", productId.toString()).param("at", at).update();
        return new UUID[]{productId, optionId, batchId};
    }

    public static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    /** 사진 행의 id 와 객체 키 */
    public record ImageRow(UUID id, String key) {
    }
}
