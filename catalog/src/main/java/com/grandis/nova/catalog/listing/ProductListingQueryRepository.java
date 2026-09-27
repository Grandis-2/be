package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 상품 목록 · 검색 · 상세의 읽기 전용 저장소. **catalog 가 다른 서비스의 표를 읽는 유일한 자리**다 —
 * preorder 소유 preorder_campaigns(opens_at · closes_at)와 order 소유 option_inventories(재고 집계)를 여기서만, SELECT 로만 읽는다.
 * 노출 조건(오픈 예정 · 마감 · 마감 + 120시간 숨김 · 품절)이 페이징 조건이라 쿼리 안에 있어야 한다. HTTP 로 받아 거르면 페이지가 깨진다.
 * 판매 중(ACTIVE) 옵션이 하나도 없는 상품(옵션 없음 · 전부 판매 중지)은 목록에 남기고 sellable=false 로 알린다 — 화면이 "판매 중지" 를 그린다
 * (사용자 결정 2026-09-27). 숨기지 않는다.
 *
 * 시각은 datetime(6) UTC 벽시계다. Instant 를 UTC LocalDateTime 으로 바꿔 넘기고 같은 방식으로 읽는다 — Timestamp 로 넘기면 JVM 시간대로 바뀐다.
 * 정렬은 계약대로 productId 내림차순 고정이다.
 */
@Repository
public class ProductListingQueryRepository {

    /** 마감 뒤 이 시간이 지나면 목록에서 숨긴다(확정 5일). */
    static final Duration HIDE_AFTER_CLOSE = Duration.ofHours(120);

    private static final String FROM_VISIBLE = """
              FROM products p
              JOIN product_registrations r ON r.product_id = p.id AND r.completed_at IS NOT NULL
              LEFT JOIN preorder_campaigns c ON c.product_id = p.id
             WHERE p.visible = 1
               AND p.status = 'ACTIVE'
               AND (p.sale_mode = 'IN_STOCK' OR (c.closes_at IS NOT NULL AND c.closes_at > :hideBefore))
            """;

    private static final String SELECT_ITEMS = """
            SELECT p.id, p.sale_mode, p.title, p.status, c.opens_at, c.closes_at,
                   (SELECT MIN(o.price) FROM product_options o
                     WHERE o.product_id = p.id AND o.status = 'ACTIVE') AS min_price,
                   EXISTS (SELECT 1 FROM product_options po
                            WHERE po.product_id = p.id AND po.status = 'ACTIVE') AS sellable,
                   (SELECT i.url FROM product_images i
                     WHERE i.product_id = p.id AND i.kind = 'GALLERY' AND i.is_primary = 1
                     ORDER BY (i.bundle_key <> ''), i.bundle_key LIMIT 1) AS image_url,
                   CASE WHEN p.sale_mode = 'IN_STOCK' AND NOT EXISTS (
                            SELECT 1 FROM product_options o
                              JOIN option_inventories inv ON inv.option_id = o.id
                             WHERE o.product_id = p.id AND o.status = 'ACTIVE'
                               AND inv.stock_total - inv.stock_reserved - inv.stock_sold > 0)
                        THEN 1 ELSE 0 END AS sold_out
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ProductListingQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(ProductListFilter filter, Instant now) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(FROM_VISIBLE);
        MapSqlParameterSource params = baseParams(now);
        appendFilters(sql, params, filter);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    public List<ProductListItem> find(ProductListFilter filter, Instant now, int page, int size) {
        StringBuilder sql = new StringBuilder(SELECT_ITEMS).append(FROM_VISIBLE);
        MapSqlParameterSource params = baseParams(now);
        appendFilters(sql, params, filter);
        sql.append(" ORDER BY p.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> toItem(rs, now));
    }

    private static MapSqlParameterSource baseParams(Instant now) {
        return new MapSqlParameterSource("hideBefore", utc(now.minus(HIDE_AFTER_CLOSE)));
    }

    private static void appendFilters(StringBuilder sql, MapSqlParameterSource params, ProductListFilter filter) {
        if (filter.saleMode() != null) {
            sql.append(" AND p.sale_mode = :saleMode");
            params.addValue("saleMode", filter.saleMode().name());
        }
        if (filter.categoryId() != null) {
            sql.append(" AND p.category_id IN (SELECT ct.id FROM categories ct WHERE ct.id = :categoryId OR ct.parent_id = :categoryId)");
            params.addValue("categoryId", filter.categoryId());
        }
        if (filter.q() != null) {
            sql.append(" AND (p.title LIKE :q ESCAPE '\\\\' OR p.tags LIKE :q ESCAPE '\\\\')");
            params.addValue("q", "%" + escapeLike(filter.q()) + "%");
        }
        if (!filter.colors().isEmpty() || !filter.storages().isEmpty()) {
            // 필터 조건은 판매 중 옵션 하나가 모든 축을 함께 만족해야 한다. 품절은 포함, 판매 중지는 제외
            sql.append(" AND EXISTS (SELECT 1 FROM product_options o WHERE o.product_id = p.id AND o.status = 'ACTIVE'");
            appendAxisCondition(sql, params, "color", filter.colors());
            appendAxisCondition(sql, params, "storage", filter.storages());
            sql.append(")");
        }
    }

    private static void appendAxisCondition(StringBuilder sql, MapSqlParameterSource params, String axisKey,
                                            List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(" AND EXISTS (SELECT 1 FROM product_option_selections s")
                .append(" JOIN product_option_axes a ON a.id = s.axis_id")
                .append(" JOIN product_option_values v ON v.id = s.value_id")
                .append(" WHERE s.option_id = o.id AND a.axis_key = :").append(axisKey).append("Axis")
                .append(" AND v.normalized_value IN (:").append(axisKey).append("Values))");
        params.addValue(axisKey + "Axis", axisKey).addValue(axisKey + "Values", values);
    }

    /** 사전예약 회차 시각. 회차가 없으면 비어 있다. */
    public Optional<CampaignWindow> findCampaign(Long productId) {
        List<CampaignWindow> rows = jdbc.query(
                "SELECT opens_at, closes_at FROM preorder_campaigns WHERE product_id = :productId",
                new MapSqlParameterSource("productId", productId),
                (rs, rowNum) -> new CampaignWindow(instant(rs, "opens_at"), instant(rs, "closes_at")));
        return rows.stream().findFirst();
    }

    /** 옵션별 가용 수량(총량 − 선점 − 판매). 재고 행이 없는 옵션은 빠진다 — 판매 불가로 읽는다. */
    public Map<Long, Integer> findAvailableQuantities(Long productId) {
        Map<Long, Integer> available = new HashMap<>();
        jdbc.query("""
                SELECT o.id, inv.stock_total - inv.stock_reserved - inv.stock_sold AS available
                  FROM product_options o
                  JOIN option_inventories inv ON inv.option_id = o.id
                 WHERE o.product_id = :productId
                """, new MapSqlParameterSource("productId", productId),
                (rs, rowNum) -> available.put(rs.getLong("id"), rs.getInt("available")));
        return available;
    }

    /** LIKE 의 와일드카드(% _ \)를 문자로 취급한다. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ProductListItem toItem(ResultSet rs, Instant now) throws SQLException {
        SaleMode saleMode = SaleMode.valueOf(rs.getString("sale_mode"));
        Instant opensAt = instant(rs, "opens_at");
        Instant closesAt = instant(rs, "closes_at");
        PreorderSaleStatus preorderStatus = saleMode == SaleMode.PREORDER && opensAt != null && closesAt != null
                ? PreorderSaleStatus.of(opensAt, closesAt, now) : null;
        BigDecimal minPrice = rs.getBigDecimal("min_price");
        return new ProductListItem(rs.getLong("id"), saleMode, rs.getString("title"), rs.getString("image_url"),
                SaleStatus.valueOf(rs.getString("status")), minPrice, rs.getBoolean("sellable"), rs.getBoolean("sold_out"),
                preorderStatus, opensAt, closesAt);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
