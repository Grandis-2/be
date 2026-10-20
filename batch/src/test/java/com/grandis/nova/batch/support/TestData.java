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
 * 검사 · 정리에 필요한 최소 업무 행을 넣는다(시험 전용 — 운영 batch 는 업무 표에 쓰지 않는다. 사진 정리의 images 삭제만 예외다).
 * 예약마다 회원을 새로 만든다 — 같은 회원 · 상품의 진행 중 예약은 하나뿐이다(uq_preorder_active).
 * 기본은 정상 데이터다 — 순번은 상품마다 1부터, 이력은 지금 상태 하나, 외부 번호는 등록 작업이 성공일 때만.
 * 어긋난 데이터는 시험이 따로 만든다.
 */
public final class TestData {

    public static final String SKU = "TEST-SKU-256";

    private final JdbcClient jdbc;
    private final Map<String, UUID[]> products = new HashMap<>();
    private final Map<String, AtomicInteger> positions = new HashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public TestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 이름별 사전예약 상품 하나(상품 · 옵션 · 배송 차수). 같은 이름이면 같은 상품이다 */
    public UUID product(String name) {
        return productRow(name)[0];
    }

    /** 상품의 배송 차수(끝이 열린 1차수, 순번 1부터) */
    public UUID batch(String name) {
        return productRow(name)[2];
    }

    /**
     * @param registerJobStatus 등록 작업 상태. null 이면 작업을 만들지 않는다
     * @param changedAt         예약 · 작업의 변경 시각
     */
    public BePreorderRow preorder(String productName, String token, String status, String registerJobStatus,
                                  Instant changedAt) {
        UUID[] product = productRow(productName);
        int n = sequence.incrementAndGet();
        int position = positions.get(productName).incrementAndGet();
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
        String number = "SUCCEEDED".equals(registerJobStatus) ? "R-TEST-" + n : null;
        jdbc.sql("""
                        INSERT INTO preorders (id, preorder_token, customer_id, product_id, option_id, shipment_batch_id,
                                               queue_position, idempotency_key, product_title_snapshot, option_title_snapshot,
                                               unit_price_snapshot, status, payable_from, reserved_at, external_reference,
                                               event_sequence, created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), :token, UUID_TO_BIN(:customer), UUID_TO_BIN(:product), UUID_TO_BIN(:option),
                                UUID_TO_BIN(:batch), :position, :idem, '시험 상품', '실버 / 256GB', 1000, :status,
                                :payableFrom, :reservedAt, :number, 1, :at, :at)
                        """)
                .param("id", preorderId.toString()).param("token", token).param("customer", customerId.toString())
                .param("product", product[0].toString()).param("option", product[1].toString())
                .param("batch", product[2].toString()).param("position", position).param("idem", "idem-" + n)
                .param("status", status)
                .param("payableFrom", payable ? at : null)
                .param("reservedAt", status.equals("RESERVED") ? at : null)
                .param("number", number)
                .param("at", at)
                .update();
        jdbc.sql("""
                        INSERT INTO preorder_events (preorder_id, event_sequence, from_status, to_status, actor, created_at)
                        VALUES (UUID_TO_BIN(:preorder), 1, NULL, :status, 'SYSTEM', :at)
                        """)
                .param("preorder", preorderId.toString()).param("status", status).param("at", at)
                .update();
        UUID registerJobId = registerJobStatus == null ? null : syncJob(preorderId, "REGISTER", registerJobStatus, at);
        return new BePreorderRow(preorderId, token, customerId, product[0], registerJobId, number);
    }

    /** 취소 작업 */
    public void cancelJob(BePreorderRow preorder, String status, Instant at) {
        syncJob(preorder.id(), "CANCEL", status, utc(at));
    }

    /**
     * 등록 작업의 발행 이벤트(REGISTER_JOB_READY)
     *
     * @param publishedAt 발행 시각. null 이면 아직 발행되지 않았다
     */
    public void registerJobReady(BePreorderRow preorder, int run, Instant createdAt, Instant publishedAt) {
        jdbc.sql("""
                        INSERT INTO preorder_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload,
                                                            created_at, published_at)
                        VALUES (:eventId, 'PREORDER_SYNC_JOB', UUID_TO_BIN(:job), 'REGISTER_JOB_READY',
                                JSON_OBJECT('syncJobId', :job, 'preorderId', :token, 'run', :run, 'jobType', 'REGISTER'),
                                :createdAt, :publishedAt)
                        """)
                .param("eventId", UUID.randomUUID().toString()).param("job", preorder.registerJobId().toString())
                .param("token", preorder.token()).param("run", run).param("createdAt", utc(createdAt))
                .param("publishedAt", publishedAt == null ? null : utc(publishedAt))
                .update();
    }

    /**
     * 등록 작업의 외부 호출 차례
     *
     * @param result 결과. null 이면 결과를 받지 못했다
     */
    public void attempt(BePreorderRow preorder, String result, Instant startedAt) {
        jdbc.sql("""
                        INSERT INTO preorder_sync_attempts (sync_job_id, attempt_number, actor, result, started_at, finished_at)
                        SELECT UUID_TO_BIN(:job), COALESCE(MAX(attempt_number), 0) + 1, 'SYSTEM', :result, :startedAt,
                               :finishedAt
                          FROM preorder_sync_attempts
                         WHERE sync_job_id = UUID_TO_BIN(:job)
                        """)
                .param("job", preorder.registerJobId().toString()).param("result", result)
                .param("startedAt", utc(startedAt)).param("finishedAt", result == null ? null : utc(startedAt))
                .update();
    }

    /**
     * 그 예약의 사전예약 주문과 결제. 주문은 결제 확인 뒤 상태(AWAITING_CONFIRMATION)다
     *
     * @param paymentStatus 결제 상태(SUCCEEDED · REFUNDED)
     * @param at            주문 · 결제의 변경 시각
     */
    public void paidOrder(BePreorderRow preorder, String paymentStatus, Instant at) {
        UUID orderId = UUID.randomUUID();
        LocalDateTime changed = utc(at);
        jdbc.sql("""
                        INSERT INTO orders (id, customer_id, source, preorder_id, preorder_token, status, total_amount,
                                            ship_to_name, ship_to_phone, ship_to_postal_code, ship_to_line1, created_at,
                                            updated_at)
                        VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:customer), 'PREORDER', UUID_TO_BIN(:preorder), :token,
                                'AWAITING_CONFIRMATION', 1000, '받는 사람', '010-0000-0000', '00000', '주소', :at, :at)
                        """)
                .param("id", orderId.toString()).param("customer", preorder.customerId().toString())
                .param("preorder", preorder.id().toString()).param("token", preorder.token()).param("at", changed)
                .update();
        jdbc.sql("""
                        INSERT INTO payments (id, target_type, target_id, provider_payment_key, amount, status, approved_at,
                                              refunded_at, created_at, updated_at)
                        VALUES (UUID_TO_BIN(UUID()), 'ORDER', UUID_TO_BIN(:order), :paymentKey, 1000, :status, :at,
                                :refundedAt, :at, :at)
                        """)
                .param("order", orderId.toString()).param("paymentKey", "test-pay-" + orderId)
                .param("status", paymentStatus)
                .param("refundedAt", paymentStatus.equals("REFUNDED") ? changed : null)
                .param("at", changed)
                .update();
    }

    /** 이력을 하나 더 남기고 예약 행의 이력 번호를 올린다 — 예약 상태는 바꾸지 않는다 */
    public void history(BePreorderRow preorder, String toStatus) {
        jdbc.sql("""
                        INSERT INTO preorder_events (preorder_id, event_sequence, from_status, to_status, actor, created_at)
                        SELECT id, event_sequence + 1, status, :status, 'SYSTEM', updated_at
                          FROM preorders WHERE id = UUID_TO_BIN(:preorder)
                        """)
                .param("preorder", preorder.id().toString()).param("status", toStatus)
                .update();
        jdbc.sql("UPDATE preorders SET event_sequence = event_sequence + 1 WHERE id = UUID_TO_BIN(:preorder)")
                .param("preorder", preorder.id().toString())
                .update();
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

    /** 시험이 넣은 결과 · 업무 행을 지운다. 외래 키 순서대로 */
    public void clear() {
        for (String table : new String[]{"reconciliation_findings", "reconciliation_runs", "image_deletions", "images",
                "draw_campaigns", "payments", "order_items", "orders",
                "preorder_outbox_events",
                "preorder_sync_attempts", "preorder_sync_jobs", "preorder_events", "preorders", "shipment_batches",
                "preorder_campaigns", "customers", "product_options", "products", "categories"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        products.clear();
        positions.clear();
    }

    private UUID syncJob(UUID preorderId, String type, String status, LocalDateTime at) {
        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO preorder_sync_jobs (id, preorder_id, job_type, request_payload, status, dead_lettered_at,
                                                        created_at, updated_at)
                        VALUES (UUID_TO_BIN(:id), UUID_TO_BIN(:preorder), :type, '{}', :status, :deadAt, :at, :at)
                        """)
                .param("id", jobId.toString()).param("preorder", preorderId.toString()).param("type", type)
                .param("status", status)
                .param("deadAt", status.equals("DEAD_LETTER") ? at : null)
                .param("at", at)
                .update();
        return jobId;
    }

    private UUID[] productRow(String name) {
        positions.computeIfAbsent(name, ignored -> new AtomicInteger());
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

    /**
     * @param registerJobId  등록 작업 id. 작업을 만들지 않았으면 null
     * @param externalNumber 외부 번호. 등록 작업이 성공일 때만 있다
     */
    public record BePreorderRow(UUID id, String token, UUID customerId, UUID productId, UUID registerJobId,
                                String externalNumber) {
    }

    /** 사진 행의 id 와 객체 키 */
    public record ImageRow(UUID id, String key) {
    }
}
