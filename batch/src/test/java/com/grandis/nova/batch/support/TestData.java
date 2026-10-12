package com.grandis.nova.batch.support;

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
 * 검사에 필요한 최소 업무 행을 넣는다(시험 전용 — 운영 batch 는 업무 표에 쓰지 않는다).
 * 예약마다 회원을 새로 만든다 — 같은 회원 · 상품의 진행 중 예약은 하나뿐이다(uq_preorder_active).
 */
public final class TestData {

    public static final String SKU = "TEST-SKU-256";

    private final JdbcClient jdbc;
    private final Map<String, UUID[]> products = new HashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public TestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 이름별 사전예약 상품 하나(상품 · 옵션 · 배송 차수). 같은 이름이면 같은 상품이다 */
    public UUID product(String name) {
        return productRow(name)[0];
    }

    /**
     * @param registerJobStatus 등록 작업 상태. null 이면 작업을 만들지 않는다
     * @param changedAt         예약 · 작업의 변경 시각
     */
    public BePreorderRow preorder(String productName, String token, String status, String registerJobStatus,
                                  Instant changedAt) {
        UUID[] product = productRow(productName);
        int n = sequence.incrementAndGet();
        UUID customerId = UUID.randomUUID();
        UUID preorderId = UUID.randomUUID();
        LocalDateTime at = utc(changedAt);
        jdbc.sql("""
                        INSERT INTO customers (id, kakao_id, display_name, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), :kakao, :name, :at, :at)
                        """)
                .param("id", customerId.toString()).param("kakao", "test-" + n).param("name", "회원" + n).param("at", at)
                .update();
        boolean payable = status.equals("REGISTERED") || status.equals("RESERVED");
        jdbc.sql("""
                        INSERT INTO preorders (id, preorder_token, customer_id, product_id, option_id, shipment_batch_id,
                                               queue_position, idempotency_key, product_title_snapshot, option_title_snapshot,
                                               unit_price_snapshot, status, payable_from, reserved_at, external_reference,
                                               created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), :token, UUID_TO_BIN(:customer), UUID_TO_BIN(:product), UUID_TO_BIN(:option),
                                UUID_TO_BIN(:batch), :position, :idem, '시험 상품', '실버 / 256GB', 1000, :status,
                                :payableFrom, :reservedAt, :number, :at, :at)
                        """)
                .param("id", preorderId.toString()).param("token", token).param("customer", customerId.toString())
                .param("product", product[0].toString()).param("option", product[1].toString())
                .param("batch", product[2].toString()).param("position", n).param("idem", "idem-" + n)
                .param("status", status)
                .param("payableFrom", payable ? at : null)
                .param("reservedAt", status.equals("RESERVED") ? at : null)
                .param("number", payable ? "R-TEST-" + n : null)
                .param("at", at)
                .update();
        if (registerJobStatus != null) {
            jdbc.sql("""
                            INSERT INTO preorder_sync_jobs (id, preorder_id, job_type, request_payload, status, dead_lettered_at,
                                                            created_at, updated_at)
                            VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:preorder), 'REGISTER', '{}', :status, :deadAt, :at, :at)
                            """)
                    .param("id", UUID.randomUUID().toString()).param("preorder", preorderId.toString())
                    .param("status", registerJobStatus)
                    .param("deadAt", registerJobStatus.equals("DEAD_LETTER") ? at : null)
                    .param("at", at)
                    .update();
        }
        return new BePreorderRow(preorderId, token, customerId, product[0]);
    }

    /** 회차. 오픈 · 마감 시각만 시험에 쓴다 */
    public void campaign(String productName, Instant opensAt, Instant closesAt) {
        UUID productId = product(productName);
        jdbc.sql("""
                        INSERT INTO preorder_campaigns (product_id, opens_at, closes_at, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:product), :opens, :closes, :opens, :opens)
                        """)
                .param("product", productId.toString()).param("opens", utc(opensAt)).param("closes", utc(closesAt))
                .update();
    }

    /** 시험이 넣은 결과 · 업무 행을 지운다. 외래 키 순서대로 */
    public void clear() {
        for (String table : new String[]{"reconciliation_findings", "reconciliation_runs", "preorder_sync_attempts",
                "preorder_sync_jobs", "preorders", "shipment_batches", "preorder_campaigns", "customers",
                "product_options", "products", "categories"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        products.clear();
    }

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
                        INSERT INTO products (id, category_id, sale_mode, title, status, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:category), 'PREORDER', :title, 'ACTIVE', :at, :at)
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

    public static String sku(String productName) {
        return SKU + "-" + productName;
    }

    public record BePreorderRow(UUID id, String token, UUID customerId, UUID productId) {
    }
}
