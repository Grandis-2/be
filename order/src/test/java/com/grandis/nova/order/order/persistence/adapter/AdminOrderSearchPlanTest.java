package com.grandis.nova.order.order.persistence.adapter;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.AdminOrderFilter;
import com.grandis.nova.order.order.domain.repository.OrderIdPrefix;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.support.GeneralLog;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 목록 조건마다 쓰는 색인(EXPLAIN). 계획은 행 수 · 분포를 보고 정해지므로 주문 3,000건(회원 300명 · 상품 60개 · 받는 분 이름은 주문마다
 * 다름)을 한 번 심고 통계를 다시 잰 뒤 본다. 목록 문장과 총계 문장을 둘 다 본다 — 한 쪽 크기를 1 로 해 총계를 꼭 세게 한다.
 * 컨테이너를 다른 시험과 함께 쓰므로 심은 행은 클래스가 끝나면 지운다.
 */
@OrderIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminOrderSearchPlanTest {

    static final int ORDERS = 3_000;
    static final int CUSTOMERS = 300;
    static final int PRODUCTS = 60;
    /** 가장 오래된 이만큼의 주문은 받는 분 이름 · 상품이 하나로 같다 — 조건이 맞는 행이 옛날에 몰린 모양(출시 때 몰린 상품 등). */
    static final int OLD = 300;
    /** 통계 표본 페이지 수. 심은 행(주문 · 주문상품 3,000건씩)을 모두 덮을 만큼 크게. */
    static final int STATS_SAMPLE_PAGES = 1000;
    /**
     * 심는 주문 id(SQL 식, n 번째). 옛 주문은 UUID v7 처럼 앞 48비트가 만든 시각(밀리초, baseMillis - n 초)이라 주문 번호 앞부분이 시각과 함께
     * 옛날에 몰린다. 나머지는 흩어진 값이다. 자리표시자 셋: baseMillis · salt · salt.
     */
    static final String ORDER_ID = "IF(n > " + (ORDERS - OLD) + ", UNHEX(CONCAT(LPAD(HEX(? - n * 1000), 12, '0'), SUBSTR(MD5(CONCAT(?, 'o', n)), 13))), "
            + "UNHEX(MD5(CONCAT(?, 'o', n))))";
    final long baseMillis = Instant.now().toEpochMilli();
    /** 심은 행의 값 앞에 붙는다. */
    final String salt = UUID.randomUUID().toString();
    private boolean seeded;

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderReader reader;
    @Autowired OrderLedger ledger;
    @Autowired TransactionTemplate transactionTemplate;

    OrderFixtures fixtures;
    Order preorder;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        preorder = new PlacedOrders(ledger, transactionTemplate, fixtures).place(fixtures.customer());
        seedOnce(preorder);
    }

    @Test
    void orderNumberPrefixReadsPrimaryKeyRange() throws Exception {
        OrderIdPrefix prefix = OrderIdPrefix.parse(preorder.id().toString().substring(0, 8)).orElseThrow();
        assertPlans(filter().orderIdPrefix(prefix).build(), hex(prefix.lowest()), "PRIMARY");
    }

    /** 옛 주문의 주문 번호(앞 8자리 — 약 65초 동안 만든 주문). 그보다 새 주문을 최신순 색인으로 훑지 않고 기본 키 범위로 읽는다. */
    @Test
    void oldOrderNumberPrefixReadsPrimaryKeyRange() throws Exception {
        String hex8 = "%012x".formatted(baseMillis - (ORDERS - OLD / 2) * 1000L).substring(0, 8);
        OrderIdPrefix prefix = OrderIdPrefix.parse(hex8).orElseThrow();
        assertPlans(filter().orderIdPrefix(prefix).build(), hex(prefix.lowest()), "PRIMARY");
    }

    @Test
    void wholeOrderIdReadsPrimaryKey() throws Exception {
        assertPlans(filter().orderIdPrefix(OrderIdPrefix.parse(preorder.id().toString()).orElseThrow()).build(), hex(preorder.id()), "PRIMARY");
    }

    @Test
    void customerReadsMemberIndex() throws Exception {
        assertPlans(filter().customerId(seededCustomer(7)).build(), hex(seededCustomer(7)), "ix_order_member_created");
    }

    @Test
    void shipToNameReadsNameIndex() throws Exception {
        String name = seededNamePrefix() + "12";
        assertPlans(filter().shipToName(name).build(), name, "ix_order_ship_to_name");
    }

    @Test
    void shipToPhoneLast4ReadsGeneratedColumnIndex() throws Exception {
        assertPlans(filter().shipToPhoneLast4("2718").build(), "'2718'", "ix_order_ship_to_phone_last4");
    }

    /**
     * 맞는 행이 옛날에 몰린 조건(전체의 10%). 최신순 색인을 거꾸로 훑으면 열 줄쯤에서 한 쪽이 찰 것 같아 보이지만, 실제로는 그보다 새 주문을 모두
     * 훑는다 — 그 계획을 고르지 않고 조건 색인으로 읽는다.
     */
    @Test
    void nameMatchingOnlyOldOrdersReadsNameIndex() throws Exception {
        String name = seededNamePrefix() + "old";
        assertPlans(filter().shipToName(name).build(), name, "ix_order_ship_to_name");
    }

    @Test
    void productOfOnlyOldOrdersReadsItemsByProductFirst() throws Exception {
        assertPlans(filter().productId(oldProduct()).build(), hex(oldProduct()), "ix_order_item_product");
    }

    @Test
    void preorderReadsTokenIndex() throws Exception {
        assertPlans(filter().preorderToken(preorder.preorderToken()).build(), preorder.preorderToken(), "uq_order_preorder_token");
    }

    @Test
    void productReadsItemsByProductFirst() throws Exception {
        assertPlans(filter().productId(seededProduct(11)).build(), hex(seededProduct(11)), "ix_order_item_product");
    }

    @Test
    void optionReadsItemsByOptionFirst() throws Exception {
        assertPlans(filter().optionId(seededOption(11)).build(), hex(seededOption(11)), "ix_order_item_option");
    }

    @Test
    void createdRangeReadsCreatedIndex() throws Exception {
        // 마이크로초를 표식으로 쓴다 — 문장에 그대로 찍힌다
        Instant from = Instant.now().minusSeconds(100).truncatedTo(ChronoUnit.SECONDS).plusNanos(314_159_000);
        assertPlans(filter().createdFrom(from).build(), "314159", "ix_order_created");
    }

    /** 좁히는 조건과 상태가 섞이면 정렬은 섞인 방향이라 어느 색인으로도 줄 수 없다 — 최신순 색인을 훑지 않고 조건 색인으로 고른 행만 정렬한다. */
    @Test
    void oldStatusWithOldNameReadsConditionIndex() throws Exception {
        String name = seededNamePrefix() + "old";
        assertPlans(filter().status(OrderStatus.CANCELED).shipToName(name).build(), name,
                "ix_order_ship_to_name", "ix_order_status_created", "ix_order_due");
    }

    /** 흔한 상태(심은 주문의 90%) + 상품. 상태 범위가 크므로 그 상품의 주문상품부터 읽는다. */
    @Test
    void commonStatusWithProductReadsItemsByProductFirst() throws Exception {
        assertPlans(filter().status(OrderStatus.DELIVERED).productId(seededProduct(11)).build(), hex(seededProduct(11)), "ix_order_item_product");
    }

    /**
     * 회원 + 상품(회원 7 의 주문은 모두 상품 7 이다 — 7 ≡ n mod 300 이면 n mod 60 도 7). 회원으로 시작하는 색인(만든 시각 · 멱등 키 — 정렬은
     * 섞인 방향이라 어느 쪽이든 그 회원의 주문만 읽고 정렬한다)이나 상품의 주문상품부터 읽는다.
     */
    @Test
    void customerWithProductReadsConditionIndex() throws Exception {
        assertPlans(filter().customerId(seededCustomer(7)).productId(seededProduct(7)).build(), hex(seededCustomer(7)),
                "ix_order_member_created", "uq_order_idempotency", "ix_order_item_product");
    }

    /** 기간 + 이름. 기간은 최신순 색인 그 자체의 범위지만, 이름이 좁히므로 정렬은 섞인 방향이고 이름 색인으로 고른다. */
    @Test
    void createdRangeWithNameReadsNameIndex() throws Exception {
        String name = seededNamePrefix() + "12";
        assertPlans(filter().createdFrom(Instant.now().minusSeconds(100)).shipToName(name).build(), name, "ix_order_ship_to_name");
    }

    /** 상품별 상태 수는 그 상품의 주문상품부터 읽는다 — 주문을 처음부터 훑으며 서브쿼리를 되풀이하지 않는다(캐시가 없어 부를 때마다 센다). */
    @Test
    void productStatusCountReadsItemsByProductFirst() throws Exception {
        String marker = hex(seededProduct(11));
        List<String> counts = GeneralLog.statements("%from orders%" + marker + "%group by%", () -> reader.countByStatusOfProduct(seededProduct(11)))
                .stream().filter(sql -> sql.startsWith("select") && sql.contains(marker)).toList();
        assertThat(counts).singleElement();
        List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + counts.getFirst());
        assertThat(plan).as(describe(counts.getFirst(), plan))
                .anySatisfy(row -> assertThat(row.get("key")).isEqualTo("ix_order_item_product"))
                .noneSatisfy(row -> assertThat(row.get("type")).isIn("ALL", "index"));
    }

    /** 조건 없는 최신순 목록은 created_at 색인을 거꾸로 읽는다 — 표 전체를 읽고 정렬하지 않는다. 총계 문장은 아무 보조 색인이나 센다. */
    @Test
    void newestFirstWithoutConditionReadsCreatedIndexBackward() throws Exception {
        String sql = listStatement(AdminOrderFilter.NONE);
        List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + sql);
        assertThat(plan).as(describe(sql, plan)).singleElement().satisfies(row -> {
            assertThat(row.get("key")).isEqualTo("ix_order_created");
            assertThat((String) row.get("Extra")).doesNotContain("filesort");
        });
    }

    /** 상태 + 최신순은 created_at 을 거꾸로 훑지 않고 (상태, 만든 시각) 색인으로 그 상태의 행만 거꾸로 읽는다 — 드문 상태도. */
    @Test
    void rareStatusReadsStatusCreatedIndex() throws Exception {
        // 한 건은 있어야 총계 문장도 나간다 — 첫 쪽이 비면 총계를 세지 않는다
        fixtures.forceStatus(preorder.id(), "AWAITING_CONFIRMATION");
        // 한 건뿐이면 상태로 시작하는 두 색인 어느 쪽이든 그 한 건만 읽는다
        assertPlans(filter().status(OrderStatus.AWAITING_CONFIRMATION).build(), "'AWAITING_CONFIRMATION'", "ix_order_status_created", "ix_order_due");
    }

    /** 옛날에 몰린 상태(심은 주문의 10%, 가장 오래된 쪽). */
    @Test
    void oldStatusReadsStatusCreatedIndex() throws Exception {
        assertPlans(filter().status(OrderStatus.CANCELED).build(), "'CANCELED'", "ix_order_status_created");
    }

    private static Filter filter() {
        return new Filter();
    }

    /**
     * 목록 · 총계 문장 모두 그 색인(keys 중 하나)을 쓰고, 어느 표도 처음부터 끝까지 읽지 않는다(type=ALL · 색인 전체를 훑는 type=index 없음). 기대 색인이
     * ix_order_created 가 아니면 orders 를 그 색인으로 읽지 않는다 — LIMIT 이 붙은 최신순에서 MySQL 이 조건 색인 대신 최신순 색인을 거꾸로 훑으며
     * 거르는 계획을 고르지 않는지 본다. 한 쪽 크기를 1 로 불러 그 계획이 가장 싸 보이게 한다.
     * 문장은 조건 값(marker — 문장에 그대로 찍히는 값)으로 고른다 — 같은 서버의 다른 작업이 orders 를 읽어도 섞이지 않게.
     */
    private void assertPlans(AdminOrderFilter filter, String marker, String... keys) throws Exception {
        List<String> selects = GeneralLog.statements("%from orders%" + marker + "%", () -> reader.findForAdmin(filter, 0, 1)).stream()
                .filter(sql -> sql.startsWith("select") && sql.contains(marker)).toList();
        assertThat(selects).as("목록과 총계").hasSize(2);
        for (String sql : selects) {
            List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + sql);
            assertThat(plan).as(describe(sql, plan))
                    .anySatisfy(row -> assertThat(row.get("key")).isIn((Object[]) keys))
                    .noneSatisfy(row -> assertThat(row.get("type")).isIn("ALL", "index"));
            if (!List.of(keys).contains("ix_order_created")) {
                assertThat(plan).as(describe(sql, plan)).noneSatisfy(row -> assertThat(row.get("key")).isEqualTo("ix_order_created"));
            }
        }
    }

    /** 조건 없는 목록 문장. 한 쪽 크기 1 · 8번째 쪽으로 불러 그 꼬리(limit 7,1)를 표식으로 고른다. */
    private String listStatement(AdminOrderFilter filter) throws Exception {
        return GeneralLog.statements("%from orders%limit 7,1", () -> reader.findForAdmin(filter, 7, 1)).stream()
                .filter(sql -> sql.startsWith("select") && sql.endsWith("limit 7,1")).findFirst().orElseThrow();
    }

    private static String describe(String sql, List<Map<String, Object>> plan) {
        return sql + " → " + plan.stream().map(row -> row.get("table") + ":" + row.get("key") + ":" + row.get("type") + ":" + row.get("Extra"))
                .collect(Collectors.joining(", "));
    }

    /** 문장에 찍히는 UUID 칸 값(x'…' 의 16진수). */
    private static String hex(UUID id) {
        return HexFormat.of().formatHex(OrderFixtures.bytes(id));
    }

    @AfterAll
    void removeSeeded() {
        // 행부터 지운다 — 표 속성을 되돌리다 실패해도 심은 행이 남아 다음 시험을 중복 키로 막지 않게
        String names = seededNames();
        jdbcTemplate.update("DELETE i FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.ship_to_name LIKE ?", names);
        jdbcTemplate.update("DELETE FROM orders WHERE ship_to_name LIKE ?", names);
        jdbcTemplate.execute("ALTER TABLE orders STATS_SAMPLE_PAGES = DEFAULT");
        jdbcTemplate.execute("ALTER TABLE order_items STATS_SAMPLE_PAGES = DEFAULT");
    }

    private String seededNames() {
        return seededNamePrefix() + "%";
    }

    private UUID seededCustomer(int n) {
        return seededId("c", n);
    }

    private UUID seededProduct(int n) {
        return seededId("p", n);
    }

    private UUID seededOption(int n) {
        return seededId("v", n);
    }

    private String seededNamePrefix() {
        return "plan-" + salt.substring(0, 8) + "-";
    }

    private UUID oldProduct() {
        return seededId("p-old", "");
    }

    private UUID seededId(String kind, Object n) {
        String hex = DigestUtils.md5DigestAsHex((salt + kind + n).getBytes(StandardCharsets.UTF_8));
        return UUID.fromString(hex.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    /**
     * 주문 하나를 본으로 칸을 베껴 {@link #ORDERS} 건을 넣는다(제약을 지키는 값을 그대로 쓴다). 주문마다 상품 줄 하나. 회원 · 상품 · 옵션은 나머지로
     * 고르고, 받는 분은 "plan-{salt 앞 8자}-{n}", 전화번호는 뒤 4자리가 n(주문마다 다름), 만든 시각은 1초씩 앞으로. 가장 오래된 {@link #OLD} 건만
     * 받는 분 "plan-{salt 앞 8자}-old" · 상품 하나(oldProduct) · 상태 CANCELED 이고, id 앞부분이 만든 시각이다(ORDER_ID).
     */
    private void seedOnce(Order template) {
        if (seeded) {
            return;
        }
        try {
            seed(template);
        } catch (RuntimeException e) {
            // 반쯤 심은 행을 남기면 다음 시험이 같은 id 로 다시 심다 중복 키로 실패해 첫 원인이 가려진다
            try {
                removeSeeded();
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        seeded = true;
    }

    private void seed(Order template) {
        String orderColumns = copiedColumns("orders", "id", "order_token", "customer_id", "ship_to_name", "ship_to_phone", "created_at",
                "source", "preorder_id", "preorder_token", "payment_due_at", "status");
        String itemColumns = copiedColumns("order_items", "id", "order_id", "product_id", "option_id");
        jdbcTemplate.update("""
                INSERT /*+ SET_VAR(cte_max_recursion_depth = %4$d) */ INTO orders (id, customer_id, ship_to_name, ship_to_phone, created_at, source, status, payment_due_at, %1$s)
                WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < %2$d)
                SELECT %6$s, UNHEX(MD5(CONCAT(?, 'c', n %% %3$d))),
                       IF(n > %5$d, CONCAT('plan-', LEFT(?, 8), '-old'), CONCAT('plan-', LEFT(?, 8), '-', n)),
                       CONCAT('010-', LPAD(n %% 10000, 4, '0'), '-', LPAD(n, 4, '0')), UTC_TIMESTAMP(6) - INTERVAL n SECOND, 'CART', IF(n > %5$d, 'CANCELED', 'DELIVERED'), UTC_TIMESTAMP(6), %1$s
                  FROM seq CROSS JOIN (SELECT %1$s FROM orders WHERE id = ?) t""".formatted(orderColumns, ORDERS, CUSTOMERS, ORDERS + 1, ORDERS - OLD, ORDER_ID),
                baseMillis, salt, salt, salt, salt, salt, OrderFixtures.bytes(template.id()));
        jdbcTemplate.update("""
                INSERT /*+ SET_VAR(cte_max_recursion_depth = %4$d) */ INTO order_items (id, order_id, product_id, option_id, %1$s)
                WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < %2$d)
                SELECT UNHEX(MD5(CONCAT(?, 'i', n))), %6$s, IF(n > %5$d, UNHEX(MD5(CONCAT(?, 'p-old'))), UNHEX(MD5(CONCAT(?, 'p', n %% %3$d)))),
                       UNHEX(MD5(CONCAT(?, 'v', n %% %3$d))), %1$s
                  FROM seq CROSS JOIN (SELECT %1$s FROM order_items WHERE order_id = ? LIMIT 1) t""".formatted(itemColumns, ORDERS, PRODUCTS, ORDERS + 1, ORDERS - OLD, ORDER_ID),
                salt, baseMillis, salt, salt, salt, salt, salt, OrderFixtures.bytes(template.id()));
        // 통계는 표본 페이지로 잰다 — 기본 표본(20쪽)이면 실행마다 어림이 조금씩 달라 두 후보가 겨루는 시험(흔한 상태 + 상품 등)이 흔들릴 수 있다.
        // 심은 크기를 모두 읽도록 표본을 늘리고 재 둔다(시험 컨테이너의 표에만 남는다 — 끝나면 기본으로 돌린다)
        jdbcTemplate.execute("ALTER TABLE orders STATS_SAMPLE_PAGES = " + STATS_SAMPLE_PAGES);
        jdbcTemplate.execute("ALTER TABLE order_items STATS_SAMPLE_PAGES = " + STATS_SAMPLE_PAGES);
        jdbcTemplate.execute("ANALYZE TABLE orders, order_items");
    }

    /** 그 표의 칸 중 except 를 뺀 것(생성 칸 제외), 쉼표로 이어. */
    private String copiedColumns(String table, String... except) {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ? AND extra NOT LIKE '%GENERATED%'
                 ORDER BY ordinal_position""", String.class, table);
        List<String> skipped = List.of(except);
        return columns.stream().filter(column -> !skipped.contains(column)).collect(Collectors.joining(", "));
    }

    /** 시험에서 쓰는 조건만 채우는 짧은 빌더. */
    private static final class Filter {
        private OrderStatus status;
        private OrderIdPrefix orderIdPrefix;
        private UUID customerId;
        private String shipToName;
        private String shipToPhoneLast4;
        private String preorderToken;
        private UUID productId;
        private UUID optionId;
        private Instant createdFrom;

        Filter status(OrderStatus value) { status = value; return this; }
        Filter orderIdPrefix(OrderIdPrefix value) { orderIdPrefix = value; return this; }
        Filter customerId(UUID value) { customerId = value; return this; }
        Filter shipToName(String value) { shipToName = value; return this; }
        Filter shipToPhoneLast4(String value) { shipToPhoneLast4 = value; return this; }
        Filter preorderToken(String value) { preorderToken = value; return this; }
        Filter productId(UUID value) { productId = value; return this; }
        Filter optionId(UUID value) { optionId = value; return this; }
        Filter createdFrom(Instant value) { createdFrom = value; return this; }

        AdminOrderFilter build() {
            return new AdminOrderFilter(status, null, orderIdPrefix, customerId, shipToName, shipToPhoneLast4, preorderToken, productId, optionId,
                    createdFrom, null);
        }
    }
}
