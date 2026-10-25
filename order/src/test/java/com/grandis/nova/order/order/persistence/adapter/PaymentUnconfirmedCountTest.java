package com.grandis.nova.order.order.persistence.adapter;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.domain.repository.PaymentUnconfirmedCounts;
import com.grandis.nova.order.support.GeneralLog;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 확인 실패 건수 — 결제창이 남은 취소 주문을 재고 반환 시각(그 시각 포함)으로 세고, 색인 범위만 읽는다(EXPLAIN).
 *
 * 취소한 장바구니 주문 {@link #ORDERS} 건을 {@link #BASE} 부터 1분씩 앞으로 심는다(n 번째는 BASE - n 분, 약 10일). 계획은 행 수 · 분포를 보고
 * 정해지므로 운영처럼 최근 24시간(1,440건)이 전체의 10% 안팎이 되게 한다 — 절반쯤이면 옵티마이저가 범위 대신 색인 전체를 훑는 계획을 고를 수 있다.
 * n 이 50 의 배수이거나 60 · 1440 · 1441 인 주문만 결제창이 남은 결제 확인 실패다 — 60 · 1440 은 1시간 · 24시간 경계 바로 위, 1441 은 24시간
 * 바로 밖이다.
 *
 * 컨테이너를 다른 시험과 함께 쓴다. 다른 시험의 주문은 지금 시각이라 먼 미래의 BASE 기준 24시간에 들지 않는다. 거꾸로 심은 2100년 행은 다른
 * 시험의 "지금 이후" 조회에 들 수 있으므로 클래스가 끝나면 지우고, 다른 시험은 그 행이 있어도 맞는 단정(같은 문장 안의 차이)으로 센다.
 */
@OrderIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentUnconfirmedCountTest {

    static final int ORDERS = 15_000;
    static final Instant BASE = Instant.parse("2100-01-01T00:00:00Z");
    /** BASE 를 DB 의 UTC 시각 글자로 — 연결 시간대에 따라 바뀌지 않게 문자열로 싣는다. */
    static final String BASE_SQL = "2100-01-01 00:00:00.000000";
    /** 심은 행의 받는 분 이름 — 지울 때 고른다. */
    final String shipToName = "unconfirmed-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderReader reader;
    @Autowired OrderLedger ledger;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeAll
    void seed() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        Order template = new PlacedOrders(ledger, transactionTemplate, fixtures).place(fixtures.customer());
        String columns = copiedColumns("id", "order_token", "ship_to_name", "created_at", "source", "preorder_id", "preorder_token",
                "payment_due_at", "status", "stock_released_at", "unconfirmed_provider_order_id");
        jdbcTemplate.update("""
                INSERT /*+ SET_VAR(cte_max_recursion_depth = %2$d) */ INTO orders (id, ship_to_name, created_at, source, status, payment_due_at,
                       stock_released_at, unconfirmed_provider_order_id, %1$s)
                WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < %3$d)
                SELECT UNHEX(MD5(CONCAT(?, n))), ?, ? - INTERVAL n MINUTE - INTERVAL 1 HOUR, 'CART', 'CANCELED', UTC_TIMESTAMP(6),
                       ? - INTERVAL n MINUTE, IF(n %% 50 = 0 OR n IN (60, 1440, 1441), CONCAT(?, '-', n), NULL), %1$s
                  FROM seq CROSS JOIN (SELECT %1$s FROM orders WHERE id = ?) t""".formatted(columns, ORDERS + 1, ORDERS),
                shipToName, shipToName, BASE_SQL, BASE_SQL, shipToName, OrderFixtures.bytes(template.id()));
        jdbcTemplate.execute("ANALYZE TABLE orders");
    }

    @AfterAll
    void removeSeeded() {
        jdbcTemplate.update("DELETE FROM orders WHERE ship_to_name = ?", shipToName);
    }

    @Test
    void countsUnconfirmedCancelsSinceEachBoundInclusive() {
        PaymentUnconfirmedCounts counts = reader.countPaymentUnconfirmed(BASE.minus(Duration.ofHours(1)), BASE.minus(Duration.ofHours(24)));

        // 1시간: 50 · 60. 24시간: 50 의 배수 28개(50 ~ 1400) + 60 + 1440. 1441 과 결제창이 없는 취소는 세지 않는다
        assertThat(counts).isEqualTo(new PaymentUnconfirmedCounts(2, 30));
    }

    @Test
    void nothingAfterAllCancels() {
        assertThat(reader.countPaymentUnconfirmed(BASE.plusSeconds(1), BASE.plusSeconds(1))).isEqualTo(new PaymentUnconfirmedCounts(0, 0));
    }

    /** 최근 24시간의 재고 반환 주문만 색인 범위로 읽고, 결제창이 남았는지는 색인 안에서 거른다 — 행을 읽지 않는다. */
    @Test
    void readsReleasedUnconfirmedIndexRangeOnly() throws Exception {
        List<String> counts = GeneralLog.statements("%unconfirmed_provider_order_id is not null%",
                        () -> reader.countPaymentUnconfirmed(BASE.minus(Duration.ofHours(1)), BASE.minus(Duration.ofHours(24))))
                .stream().filter(sql -> sql.startsWith("select") && sql.contains("unconfirmed_provider_order_id is not null")).toList();
        assertThat(counts).singleElement();
        List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + counts.getFirst());
        assertThat(plan).as(describe(counts.getFirst(), plan)).singleElement().satisfies(row -> {
            assertThat(row.get("key")).isEqualTo("ix_order_released_unconfirmed");
            assertThat(row.get("type")).isEqualTo("range");
            assertThat((String) row.get("Extra")).contains("Using index");
        });
    }

    private static String describe(String sql, List<Map<String, Object>> plan) {
        return sql + " → " + plan.stream().map(row -> row.get("table") + ":" + row.get("key") + ":" + row.get("type") + ":" + row.get("Extra"))
                .collect(Collectors.joining(", "));
    }

    /** orders 의 칸 중 except 를 뺀 것(생성 칸 제외), 쉼표로 이어. */
    private String copiedColumns(String... except) {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'orders' AND extra NOT LIKE '%GENERATED%'
                 ORDER BY ordinal_position""", String.class);
        List<String> skipped = List.of(except);
        return columns.stream().filter(column -> !skipped.contains(column)).collect(Collectors.joining(", "));
    }
}
