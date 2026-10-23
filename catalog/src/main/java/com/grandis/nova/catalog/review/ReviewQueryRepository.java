package com.grandis.nova.catalog.review;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 리뷰 목록 읽기. catalog 표(product_reviews · products · categories)만 읽는다.
 * 정렬은 최신순(작성 시각 내림차순, 같으면 id 내림차순) 고정, 오프셋 페이징이다. 인덱스 ix_review_product · ix_review_customer
 * ((상품 · 회원, created_at) + PK)의 순서와 같아 따로 정렬하지 않는다(실측 EXPLAIN: id 만으로 정렬하면 Using filesort, 이 순서면
 * Backward index scan). 목록은 모두 건수와 한 문장으로 읽는다({@link #page}) — 안쪽은 단독 문장과 같은 계획이고, 바깥이 쪽 크기만큼의 행만 같은 순서로
 * 다시 정렬한다(실측 EXPLAIN ANALYZE, 리뷰 2만: 상품별은 ix_review_product, 내 리뷰는 ix_review_customer 역순, 모아보기(공개 상품 전체 · 카테고리)는
 * 상품을 훑어 ix_review_product 로 붙인 뒤 정렬한다 — 상품 조건이 리뷰 표 밖에 있다). DB 가 하는 일은 건수 · 쪽 두 문장의 합과 비슷하고, 줄어드는 것은 왕복과
 * 트랜잭션 준비 · 정리 문장이다.
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

    /** 건수를 쪽과 한 문장으로 읽을 때의 건수 파생 표({@link #page}). */
    private static final String COUNT_TOTAL = """
            SELECT COUNT(*) AS total
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
    public OffsetPage<ReviewView> pageByProduct(UUID productId, int page, int size) {
        return page(" WHERE r.product_id = :productId", new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)), page, size, false);
    }

    /** 모아보기 — 공개된 상품의 리뷰만. categoryId 가 null 이면 전체. 쪽과 건수를 한 문장으로({@link #page}). */
    public OffsetPage<ReviewView> pageVisible(UUID categoryId, int page, int size) {
        return page(visibleWhere(categoryId), visibleParams(categoryId), page, size, false);
    }

    /** 내 리뷰 — 상품 공개 여부와 상관없이 전부. orderItemId 를 주면 그 주문상품의 리뷰만(0건 또는 1건). 쪽과 건수를 한 문장으로({@link #page}). */
    public OffsetPage<ReviewView> pageMine(UUID customerId, UUID orderItemId, int page, int size) {
        return page(mineWhere(orderItemId), mineParams(customerId, orderItemId), page, size, true);
    }

    /** 내 리뷰 한 쪽(건수 없이) — 방금 쓰거나 고친 리뷰를 다시 읽을 때. */
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

    /**
     * 한 쪽과 전체 건수를 한 문장으로 — 건수 한 행에 그 쪽을 LATERAL 로 붙인다(상품 목록 findPage 와 같은 모양). 한 문장이라 READ COMMITTED 에서도
     * 건수와 쪽이 같은 시점이고 트랜잭션을 열지 않는다. 쪽이 범위 밖이어도 건수 행이 남아 total 이 그대로다. 쪽 안은 인덱스 순서로 읽고, 파생 표의
     * 순서는 바깥 문장이 보장하지 않아 같은 순서로 다시 세운다.
     */
    private OffsetPage<ReviewView> page(String where, MapSqlParameterSource params, int page, int size, boolean withOrderItem) {
        params.addValue("limit", size).addValue("offset", (long) page * size);
        String sql = "SELECT c.total, x.* FROM (" + COUNT_TOTAL + where + ") c"
                + " LEFT JOIN LATERAL (" + SELECT + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset) x ON TRUE"
                + " ORDER BY x.created_at DESC, x.id DESC";
        long[] total = {0};
        List<ReviewView> items = new ArrayList<>();
        jdbc.query(sql, params, (RowCallbackHandler) rs -> {
            total[0] = rs.getLong("total");
            if (rs.getBytes("id") != null) {   // 빈 쪽이면 건수 행 하나만 온다(x 의 칸은 NULL)
                items.add(toView(rs, withOrderItem));
            }
        });
        return OffsetPage.of(items, page, size, total[0]);
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
