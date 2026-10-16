package com.grandis.nova.order.stock.persistence.adapter;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.StockHold;
import com.grandis.nova.order.stock.domain.model.StockBalance;
import com.grandis.nova.order.stock.domain.repository.StockExpectations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 재고 장부와 주문 기준 수량을 SQL 한 문장으로 맞댄다 — 주문상품(⋈ 주문) · 반영 전 드로우 회차 · 재고 행을 옵션별로 모아(UNION ALL) 더한다.
 * 한 문장이라 READ COMMITTED 에서도 세 표를 같은 시점으로 읽는다. 상태의 분류는 {@link OrderStatus#stockHold()} 에서 만든다 — 상태가 늘면
 * 그 switch 가 컴파일러로 드러나고 여기 IN 목록은 따라온다.
 *
 * 전체 대조는 표 전체를 훑으므로 문장 실행 시간에 상한({@link #MAX_EXECUTION_MILLIS})을 건다 — 넘으면 오류로 끝나고 대조가 실패로 남는다.
 * 어긋난 옵션 수는 창 함수(COUNT(*) OVER ())로 LIMIT 전에 세어 행마다 싣는다 — 수와 앞 limit 개가 한 문장이다.
 */
@Repository
class JdbcStockExpectations implements StockExpectations {

    static final long MAX_EXECUTION_MILLIS = 120_000;

    private static final String BALANCE_COLUMNS = """
            b.option_id, po.product_id, b.registered, b.total, b.actual_reserved, b.actual_sold, b.expected_reserved, b.expected_sold""";

    private static final String DRIFTED = """
            SELECT /*+ MAX_EXECUTION_TIME(%d) */ %s, COUNT(*) OVER () AS drift_total
              FROM (%s
                    HAVING SUM(expected_reserved) <> SUM(actual_reserved) OR SUM(expected_sold) <> SUM(actual_sold)) b
              LEFT JOIN product_options po ON po.id = b.option_id
             ORDER BY b.option_id
             LIMIT :limit
            """.formatted(MAX_EXECUTION_MILLIS, BALANCE_COLUMNS, byOption(""));

    private static final String ONE = """
            SELECT %s
              FROM (%s) b
              LEFT JOIN product_options po ON po.id = b.option_id
            """.formatted(BALANCE_COLUMNS, byOption("AND %s = :optionId"));

    private final NamedParameterJdbcTemplate jdbc;

    JdbcStockExpectations(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Drifts findDrifted(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 은 1 이상: " + limit);
        }
        List<StockBalance> first = new ArrayList<>();
        int[] total = {0};
        jdbc.query(DRIFTED, holds().addValue("limit", limit), rs -> {
            total[0] = rs.getInt("drift_total");
            first.add(balance(rs, first.size()));
        });
        return new Drifts(total[0], first);
    }

    @Override
    public StockBalance balanceOf(UUID optionId) {
        return jdbc.query(ONE, holds().addValue("optionId", UuidBinary.toBytes(optionId)), JdbcStockExpectations::balance)
                .stream().findFirst()
                .orElseGet(() -> new StockBalance(optionId, null, false, 0, 0, 0, 0, 0));
    }

    /**
     * 옵션별 합. 칸 순서: 주문 기준 확보 · 판매, 재고 행 유무 · 총량 · 확보 · 판매. 사전예약 주문은 재고를 두지 않아 뺀다.
     * 반환 표식 없는 취소(NONE)는 판매로 센다 — {@link StockHold#NONE}.
     *
     * @param filter 각 갈래의 WHERE 에 붙일 조건. %s 는 그 갈래의 option_id 칸 이름이다
     */
    private static String byOption(String filter) {
        return """
                SELECT option_id, MAX(registered) AS registered, SUM(total) AS total,
                       SUM(actual_reserved) AS actual_reserved, SUM(actual_sold) AS actual_sold,
                       SUM(expected_reserved) AS expected_reserved, SUM(expected_sold) AS expected_sold
                  FROM (SELECT oi.option_id,
                               SUM(CASE WHEN o.status IN (:reserved) THEN oi.quantity ELSE 0 END) AS expected_reserved,
                               SUM(CASE WHEN o.status IN (:sold) OR (o.status IN (:none) AND o.stock_released_at IS NULL)
                                        THEN oi.quantity ELSE 0 END) AS expected_sold,
                               0 AS registered, 0 AS total, 0 AS actual_reserved, 0 AS actual_sold
                          FROM order_items oi
                          JOIN orders o ON o.id = oi.order_id
                         WHERE o.source <> :preorder %s
                         GROUP BY oi.option_id
                        UNION ALL
                        SELECT option_id, SUM(winner_count), 0, 0, 0, 0, 0
                          FROM draw_campaigns
                         WHERE settled_at IS NULL %s
                         GROUP BY option_id
                        UNION ALL
                        SELECT option_id, 0, 0, 1, stock_total, stock_reserved, stock_sold
                          FROM option_inventories
                         WHERE TRUE %s) ledger
                 GROUP BY option_id
                """.formatted(filter.formatted("oi.option_id"), filter.formatted("option_id"), filter.formatted("option_id"));
    }

    private static MapSqlParameterSource holds() {
        return new MapSqlParameterSource("preorder", OrderSource.PREORDER.name())
                .addValue("reserved", statuses(StockHold.RESERVED))
                .addValue("sold", statuses(StockHold.SOLD))
                .addValue("none", statuses(StockHold.NONE));
    }

    private static List<String> statuses(StockHold hold) {
        return Arrays.stream(OrderStatus.values()).filter(status -> status.stockHold() == hold).map(Enum::name).toList();
    }

    private static StockBalance balance(ResultSet rs, int rowNum) throws SQLException {
        byte[] productId = rs.getBytes("product_id");
        return new StockBalance(UuidBinary.fromBytes(rs.getBytes("option_id")), productId == null ? null : UuidBinary.fromBytes(productId),
                rs.getInt("registered") > 0, rs.getInt("total"), rs.getInt("actual_reserved"), rs.getInt("actual_sold"),
                rs.getLong("expected_reserved"), rs.getLong("expected_sold"));
    }
}
