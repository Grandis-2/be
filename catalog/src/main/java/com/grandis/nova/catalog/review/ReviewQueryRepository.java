package com.grandis.nova.catalog.review;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 리뷰 목록 읽기. catalog 표(product_reviews · products · categories)만 읽는다.
 * 정렬은 최신순(작성 시각 내림차순, 같으면 id 내림차순) 고정, 오프셋 페이징이다. 인덱스 ix_review_product · ix_review_customer
 * ((상품 · 회원, created_at) + PK)의 순서와 같아 따로 정렬하지 않는다(실측 EXPLAIN: id 만으로 정렬하면 Using filesort, 이 순서면
 * Backward index scan). 상품별 · 내 리뷰가 그렇고, 모아보기(공개 상품 전체 · 카테고리)는 정렬한다 — 상품 조건이 리뷰 표 밖에 있다.
 * 시각은 datetime(6) UTC 벽시계라 UTC LocalDateTime 으로 읽는다. id 는 BINARY(16) 이라 {@link UuidBinary} 로 바인딩하고 읽는다.
 */
@Repository
public class ReviewQueryRepository {

    private static final String SELECT = """
            SELECT r.id, r.product_id, p.title, r.option_title_snapshot, r.rating, r.body, r.author_name,
                   r.created_at, r.updated_at, r.order_item_id,
                   p.thumbnail_url AS image_url
              FROM product_reviews r
              JOIN products p ON p.id = r.product_id
            """;

    private static final String COUNT = """
            SELECT COUNT(*)
              FROM product_reviews r
              JOIN products p ON p.id = r.product_id
            """;

    /** 상위 카테고리를 고르면 하위에 배정된 상품도 포함한다(상품 목록의 categoryId 와 같은 규칙). */
    private static final String IN_CATEGORY =
            " AND p.category_id IN (SELECT ct.id FROM categories ct WHERE ct.id = :categoryId OR ct.parent_id = :categoryId)";

    private final NamedParameterJdbcTemplate jdbc;

    public ReviewQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 한 상품의 리뷰. 상품을 보여 줄지는 호출자가 먼저 판정한다(상품 상세와 같은 규칙). */
    public long countByProduct(UUID productId) {
        return count(" WHERE r.product_id = :productId", new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)));
    }

    public List<ReviewView> findByProduct(UUID productId, int page, int size) {
        return find(" WHERE r.product_id = :productId", new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)),
                page, size, false);
    }

    /** 모아보기 — 공개된 상품의 리뷰만. categoryId 가 null 이면 전체. */
    public long countVisible(UUID categoryId) {
        return count(visibleWhere(categoryId), visibleParams(categoryId));
    }

    public List<ReviewView> findVisible(UUID categoryId, int page, int size) {
        return find(visibleWhere(categoryId), visibleParams(categoryId), page, size, false);
    }

    /** 내 리뷰 — 상품 공개 여부와 상관없이 전부. orderItemId 를 주면 그 주문상품의 리뷰만(0건 또는 1건). */
    public long countMine(UUID customerId, UUID orderItemId) {
        return count(mineWhere(orderItemId), mineParams(customerId, orderItemId));
    }

    public List<ReviewView> findMine(UUID customerId, UUID orderItemId, int page, int size) {
        return find(mineWhere(orderItemId), mineParams(customerId, orderItemId), page, size, true);
    }

    private static String visibleWhere(UUID categoryId) {
        return " WHERE p.visible = 1" + (categoryId == null ? "" : IN_CATEGORY);
    }

    private static MapSqlParameterSource visibleParams(UUID categoryId) {
        return new MapSqlParameterSource("categoryId", UuidBinary.toBytes(categoryId));
    }

    private static String mineWhere(UUID orderItemId) {
        return " WHERE r.customer_id = :customerId" + (orderItemId == null ? "" : " AND r.order_item_id = :orderItemId");
    }

    private static MapSqlParameterSource mineParams(UUID customerId, UUID orderItemId) {
        return new MapSqlParameterSource("customerId", UuidBinary.toBytes(customerId))
                .addValue("orderItemId", UuidBinary.toBytes(orderItemId));
    }

    private long count(String where, MapSqlParameterSource params) {
        Long count = jdbc.queryForObject(COUNT + where, params, Long.class);
        return count == null ? 0 : count;
    }

    private List<ReviewView> find(String where, MapSqlParameterSource params, int page, int size, boolean withOrderItem) {
        params.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(SELECT + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset", params,
                (rs, rowNum) -> toView(rs, withOrderItem));
    }

    private static ReviewView toView(ResultSet rs, boolean withOrderItem) throws SQLException {
        return new ReviewView(uuid(rs, "id"), uuid(rs, "product_id"), rs.getString("title"),
                rs.getString("option_title_snapshot"), rs.getString("image_url"), rs.getInt("rating"), rs.getString("body"),
                rs.getString("author_name"), utc(rs, "created_at"), utc(rs, "updated_at"),
                withOrderItem ? uuid(rs, "order_item_id") : null);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UuidBinary.fromBytes(rs.getBytes(column));
    }

    private static Instant utc(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
