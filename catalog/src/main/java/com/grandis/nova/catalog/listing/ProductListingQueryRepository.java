package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.product.Brand;
import com.grandis.nova.catalog.product.Discount;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.common.UuidBinary;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 상품 목록 · 검색 · 상세 · 관리자 목록의 읽기 전용 저장소. **catalog 가 다른 서비스의 표를 읽는 유일한 자리**다 —
 * preorder 소유 preorder_campaigns(opens_at · closes_at · 행의 유무)와 order 소유 option_inventories(재고 집계 · 행의 유무)를
 * 여기서만, SELECT 로만 읽는다.
 * 판매 방식별 준비({@link #READY})도 여기서 판정한다 — 등록 이벤트를 받은 preorder · order 가 자기 표에 행을 만들어야 노출된다.
 * 노출 조건(오픈 예정 · 마감 · 마감 + 120시간 숨김 · 품절)이 페이징 조건이라 쿼리 안에 있어야 한다. HTTP 로 받아 거르면 페이지가 깨진다.
 * 판매 중(ACTIVE) 옵션이 하나도 없는 상품(전부 판매 중지)은 목록에 남기고 sellable=false 로 알린다 — 화면이 "판매 중지" 를 그린다.
 * 옵션이 아예 없는 상품은 등록으로 생기지 않는다(조합 0 은 400, 옵션 삭제 경로 없음). 일반 상품이면 재고 행을 만들 옵션이 없어 준비될 수 없다
 * (사용자 결정 2026-09-27). 숨기지 않는다.
 *
 * 시각은 datetime(6) UTC 벽시계다. Instant 를 UTC LocalDateTime 으로 바꿔 넘기고 같은 방식으로 읽는다 — Timestamp 로 넘기면 JVM 시간대로 바뀐다.
 * id 는 BINARY(16) 이라 {@link UuidBinary} 로 바인딩하고 읽는다.
 * 회원 목록의 정렬은 {@link ProductSort}(최신순 · 가격순), 관리자 목록은 최신순(등록 시각 내림차순, 같으면 id 내림차순) 고정이다.
 */
@Repository
public class ProductListingQueryRepository {

    /** 마감 뒤 이 시간이 지나면 목록에서 숨긴다(확정 5일). */
    static final Duration HIDE_AFTER_CLOSE = Duration.ofHours(120);

    /**
     * 판매 방식별 준비 — 등록 이벤트를 받은 서비스가 자기 표에 행을 만들었는가. 사전예약은 preorder 회차 행, 일반은 그 상품 옵션의
     * order 재고 행(초기화는 한 상품의 옵션을 한 트랜잭션에서 모두 만들므로 하나라도 있으면 등록분은 다 있다). products 를 p 로 둔 문장에 넣는다.
     */
    static final String READY = """
            (CASE WHEN p.sale_mode = 'PREORDER'
                  THEN EXISTS (SELECT 1 FROM preorder_campaigns rc WHERE rc.product_id = p.id)
                  ELSE EXISTS (SELECT 1 FROM product_options ro JOIN option_inventories ri ON ri.option_id = ro.id
                                WHERE ro.product_id = p.id) END)""";

    private static final String FROM_VISIBLE = """
              FROM products p
              LEFT JOIN preorder_campaigns c ON c.product_id = p.id
             WHERE p.visible = 1
               AND p.status = 'ACTIVE'
               AND (p.sale_mode = 'IN_STOCK' OR (c.closes_at IS NOT NULL AND c.closes_at > :hideBefore))
               AND\s""" + READY + "\n";

    /**
     * 판매 중 옵션의 최저 정가. 할인가는 정가에 대해 줄지 않으므로(같은 상품은 할인율이 하나) 최저 정가에 할인을 건 값이 최저 할인가다 —
     * 옵션마다 할인가를 계산해 MIN 하지 않는다.
     */
    private static final String MIN_LIST_PRICE = """
            (SELECT MIN(o.price) FROM product_options o
                     WHERE o.product_id = p.id AND o.status = 'ACTIVE')""";

    private static final String SELECT_ITEMS = """
            SELECT p.id, p.sale_mode, p.title, p.brand, p.model_name, p.status, p.discount_rate, c.opens_at, c.closes_at,
                   %1$s AS min_list_price,
                   %2$s AS min_price,
                   EXISTS (SELECT 1 FROM product_options po
                            WHERE po.product_id = p.id AND po.status = 'ACTIVE') AS sellable,
                   p.thumbnail_url AS image_url,
                   CASE WHEN p.sale_mode = 'IN_STOCK' AND NOT EXISTS (
                            SELECT 1 FROM product_options o
                              JOIN option_inventories inv ON inv.option_id = o.id
                             WHERE o.product_id = p.id AND o.status = 'ACTIVE'
                               AND inv.stock_total - inv.stock_reserved - inv.stock_sold > 0)
                        THEN 1 ELSE 0 END AS sold_out
            """.formatted(MIN_LIST_PRICE, Discount.sql(MIN_LIST_PRICE, "p.discount_rate"));

    /** 관리자 목록 — 노출 규칙 없이 전부. */
    private static final String FROM_ALL = """
              FROM products p
              LEFT JOIN preorder_campaigns c ON c.product_id = p.id
             WHERE 1 = 1
            """;

    private static final String ADMIN_COLUMNS = """
            ,
                   p.visible, %s AS ready,
                   (SELECT COUNT(*) FROM product_options oc WHERE oc.product_id = p.id) AS option_count
            """.formatted(READY);

    /** 건수는 products 와 필터만 본다 — 회차는 건수에 영향이 없다(product_id 가 PRIMARY KEY 라 행이 안 는다). */
    private static final String COUNT_FROM = """
              FROM products p
             WHERE 1 = 1
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

    public List<ProductListItem> find(ProductListFilter filter, ProductSort sort, Instant now, int page, int size) {
        StringBuilder sql = new StringBuilder(SELECT_ITEMS).append(FROM_VISIBLE);
        MapSqlParameterSource params = baseParams(now);
        appendFilters(sql, params, filter);
        sql.append(sort.orderBy()).append(" LIMIT :limit OFFSET :offset");
        params.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> toItem(rs, now));
    }

    public long countForAdmin(AdminProductListFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(COUNT_FROM);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendAdminFilters(sql, params, filter);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    /** 회원 목록과 같은 열(최저가 · 썸네일 · 판매 가능 · 품절 · 회차)에 공개 여부 · 판매 방식별 준비 · 옵션 수를 더해 읽는다. */
    public List<AdminProductListItem> findForAdmin(AdminProductListFilter filter, Instant now, int page, int size) {
        StringBuilder sql = new StringBuilder(SELECT_ITEMS.stripTrailing()).append(ADMIN_COLUMNS).append(FROM_ALL);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendAdminFilters(sql, params, filter);
        sql.append(" ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> AdminProductListItem.of(toItem(rs, now),
                rs.getBoolean("visible"), rs.getBoolean("ready"), rs.getInt("option_count")));
    }

    private static void appendAdminFilters(StringBuilder sql, MapSqlParameterSource params, AdminProductListFilter filter) {
        if (filter.saleMode() != null) {
            sql.append(" AND p.sale_mode = :saleMode");
            params.addValue("saleMode", filter.saleMode().name());
        }
        if (filter.status() != null) {
            sql.append(" AND p.status = :status");
            params.addValue("status", filter.status().name());
        }
        appendTextFilter(sql, params, filter.q());
        appendBrandFilter(sql, params, filter.brands());
    }

    private static void appendBrandFilter(StringBuilder sql, MapSqlParameterSource params, List<Brand> brands) {
        if (!brands.isEmpty()) {
            sql.append(" AND p.brand IN (:brands)");
            params.addValue("brands", brands.stream().map(Brand::name).toList());
        }
    }

    private static void appendTextFilter(StringBuilder sql, MapSqlParameterSource params, String q) {
        if (q != null) {
            sql.append(" AND (p.title LIKE :q ESCAPE '\\\\' OR p.tags LIKE :q ESCAPE '\\\\'"
                    + " OR p.model_name LIKE :q ESCAPE '\\\\' OR p.brand LIKE :q ESCAPE '\\\\')");
            params.addValue("q", "%" + escapeLike(q) + "%");
        }
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
            params.addValue("categoryId", UuidBinary.toBytes(filter.categoryId()));
        }
        appendTextFilter(sql, params, filter.q());
        appendBrandFilter(sql, params, filter.brands());
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
        // ->> 의 결과는 utf8mb4_bin 이라 그대로 비교하면 대소문자 · 악센트를 가린다(black 으로 Black 이 안 걸린다) — 표 시절 값 칸과 같은 콜레이션(같은 축의 중복 판정과도 같다)으로 맞춘다
        sql.append(" AND (o.filter_attributes->>'$.").append(axisKey).append("') COLLATE utf8mb4_0900_ai_ci IN (:")
                .append(axisKey).append("Values)");
        params.addValue(axisKey + "Values", values);
    }

    /**
     * 그 상품의 판매 방식별 준비({@link #READY}). 상품이 없으면 false.
     * 공개 여부와 함께 판정하는 자리에서 이것을 따로 부르려면 REPEATABLE READ 트랜잭션 안이어야 한다(회원 · 관리자 상세) — READ COMMITTED 는
     * 문장마다 스냅샷을 새로 잡아, 두 문장 사이에 공개 전환과 회차 생성이 커밋되면 한순간도 없던 조합이 나온다. 그 밖에서는 {@link #findExposure} 를 쓴다.
     */
    public boolean isReady(UUID productId) {
        List<Boolean> rows = jdbc.query("SELECT " + READY + " AS ready FROM products p WHERE p.id = :productId",
                new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)), (rs, rowNum) -> rs.getBoolean("ready"));
        return !rows.isEmpty() && rows.getFirst();
    }

    /**
     * 공개 여부 · 판매 상태 · 판매 방식별 준비를 한 문장으로 읽는다. 사전예약 접수가 이 셋을 함께 보고 판정하므로(내부 조회 API)
     * 같은 스냅샷이어야 한다. 상품이 없으면 비어 있다.
     */
    public Optional<Exposure> findExposure(UUID productId) {
        List<Exposure> rows = jdbc.query("SELECT p.visible, p.status, " + READY + " AS ready FROM products p WHERE p.id = :productId",
                new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)),
                (rs, rowNum) -> new Exposure(rs.getBoolean("visible"), SaleStatus.valueOf(rs.getString("status")), rs.getBoolean("ready")));
        return rows.stream().findFirst();
    }

    /**
     * 여러 상품의 공개 여부 · 판매 상태 · 판매 방식별 준비를 한 문장으로 읽는다({@link #findExposure} 의 일괄판). 없는 상품은 빠진다.
     * 호출자가 옵션과 같은 스냅샷으로 읽도록 REPEATABLE READ 트랜잭션 안에서 부른다.
     */
    public Map<UUID, Exposure> findExposures(Collection<UUID> productIds) {
        Map<UUID, Exposure> exposures = new HashMap<>();
        if (productIds.isEmpty()) {
            return exposures;
        }
        jdbc.query("SELECT p.id, p.visible, p.status, " + READY + " AS ready FROM products p WHERE p.id IN (:productIds)",
                new MapSqlParameterSource("productIds", productIds.stream().map(UuidBinary::toBytes).toList()),
                rs -> {
                    exposures.put(UuidBinary.fromBytes(rs.getBytes("id")),
                            new Exposure(rs.getBoolean("visible"), SaleStatus.valueOf(rs.getString("status")), rs.getBoolean("ready")));
                });
        return exposures;
    }

    /** 한 문장으로 읽은 노출 판정 재료. */
    public record Exposure(boolean visible, SaleStatus status, boolean ready) {
    }

    /** 사전예약 회차 시각. 회차가 없으면 비어 있다. */
    public Optional<CampaignWindow> findCampaign(UUID productId) {
        List<CampaignWindow> rows = jdbc.query(
                "SELECT opens_at, closes_at FROM preorder_campaigns WHERE product_id = :productId",
                new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)),
                (rs, rowNum) -> new CampaignWindow(instant(rs, "opens_at"), instant(rs, "closes_at")));
        return rows.stream().findFirst();
    }

    /**
     * 증정품 후보 옵션 — 판매 중이고 재고 행이 있는 옵션. 상품 쪽 조건(일반 판매 · 판매 중)은 {@link #GIFT_PRODUCTS} 가 건다.
     * 재고 행이 있으면 판매 방식별 준비({@link #READY})도 참이다(일반 상품의 준비가 곧 재고 행의 유무).
     */
    private static final String GIFT_OPTION = """
            SELECT 1 FROM product_options go JOIN option_inventories gi ON gi.option_id = go.id
             WHERE go.product_id = p.id AND go.status = 'ACTIVE'""";

    /** 증정품 후보 상품 — 일반 판매 · 판매 중이고 후보 옵션이 하나 이상. 공개 여부는 보지 않는다. */
    private static final String GIFT_PRODUCTS = """
              FROM products p
             WHERE p.sale_mode = 'IN_STOCK' AND p.status = 'ACTIVE'
               AND EXISTS (%s)
            """.formatted(GIFT_OPTION);

    public long countGiftCandidates(String q) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(GIFT_PRODUCTS);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendTextFilter(sql, params, q);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    /** 증정품 후보 상품 한 쪽과 그 후보 옵션들. 상품은 관리자 목록과 같은 최신순, 옵션은 등록 순. 같은 스냅샷이 되게 REPEATABLE READ 안에서 부른다. */
    public List<GiftCandidate> findGiftCandidates(String q, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT p.id, p.title, p.thumbnail_url, p.visible").append(GIFT_PRODUCTS);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendTextFilter(sql, params, q);
        sql.append(" ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<GiftCandidate> products = jdbc.query(sql.toString(), params, (rs, rowNum) -> new GiftCandidate(
                UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("title"), rs.getString("thumbnail_url"), rs.getBoolean("visible"), List.of()));
        if (products.isEmpty()) {
            return products;
        }
        Map<UUID, List<GiftCandidate.Option>> options = new HashMap<>();
        jdbc.query("""
                SELECT o.product_id, o.id, o.sku, o.title, inv.stock_total - inv.stock_reserved - inv.stock_sold AS available
                  FROM product_options o
                  JOIN option_inventories inv ON inv.option_id = o.id
                 WHERE o.product_id IN (:productIds) AND o.status = 'ACTIVE'
                 ORDER BY o.product_id, o.created_at, o.id
                """, new MapSqlParameterSource("productIds", products.stream().map(c -> UuidBinary.toBytes(c.productId())).toList()),
                rs -> {
                    options.computeIfAbsent(UuidBinary.fromBytes(rs.getBytes("product_id")), id -> new ArrayList<>())
                            .add(new GiftCandidate.Option(UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("sku"), rs.getString("title"),
                                    rs.getInt("available")));
                });
        return products.stream()
                .map(c -> new GiftCandidate(c.productId(), c.title(), c.imageUrl(), c.visible(), List.copyOf(options.getOrDefault(c.productId(), List.of()))))
                .toList();
    }

    /** 옵션별 가용 수량(총량 − 선점 − 판매). 재고 행이 없는 옵션은 빠진다 — 판매 불가로 읽는다. */
    public Map<UUID, Integer> findAvailableQuantities(UUID productId) {
        Map<UUID, Integer> available = new HashMap<>();
        jdbc.query("""
                SELECT o.id, inv.stock_total - inv.stock_reserved - inv.stock_sold AS available
                  FROM product_options o
                  JOIN option_inventories inv ON inv.option_id = o.id
                 WHERE o.product_id = :productId
                """, new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)),
                (rs, rowNum) -> available.put(UuidBinary.fromBytes(rs.getBytes("id")), rs.getInt("available")));
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
        BigDecimal minListPrice = rs.getBigDecimal("min_list_price");
        return new ProductListItem(UuidBinary.fromBytes(rs.getBytes("id")), saleMode, rs.getString("title"),
                Brand.valueOf(rs.getString("brand")), rs.getString("model_name"), rs.getString("image_url"),
                SaleStatus.valueOf(rs.getString("status")), minPrice, minListPrice, rs.getInt("discount_rate"), rs.getBoolean("sellable"), rs.getBoolean("sold_out"),
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
