package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.product.Brand;
import com.grandis.nova.catalog.product.Discount;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.UuidBinary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 상품 목록 · 검색 · 상세 · 관리자 목록의 읽기 전용 저장소. **catalog 가 다른 서비스의 표를 읽는 유일한 자리**다 —
 * preorder 소유 preorder_campaigns(opens_at · closes_at · 행의 유무)와 order 소유 option_inventories(재고 집계 — 품절 · 남은 수량 · 증정품
 * 후보)를 여기서만, SELECT 로만 읽는다.
 * 판매 방식별 준비({@link #READY})도 여기서 판정한다 — 사전예약은 preorder 의 회차 행, 일반은 order 가 재고 행을 만들었다고 알린 이벤트로 채운
 * products.stock_ready_at 이다(재고 표의 행 유무는 보지 않는다).
 * 노출 조건(판매 방식별 준비 · 공개 · 판매 중 · 사전예약은 접수 중일 때만)이 페이징 조건이라 쿼리 안에 있어야 한다. HTTP 로 받아 거르면 페이지가 깨진다.
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

    /**
     * 상품마다 그 상품의 옵션을 찾는 서브쿼리의 계획을 통계와 상관없이 고정한다.
     * <ul>
     *   <li>옵션은 {@link #BY_PRODUCT} — (product_id, id) 로 찾는다.</li>
     *   <li>옵션 · 재고를 잇는 조인은 옵션부터 읽고({@code JOIN_ORDER}) 재고는 {@link #INVENTORY_BY_OPTION} 으로 붙인다 — 안 그러면 재고 표 통계만
     *       틀릴 때 "재고 전부 → 옵션을 uq_option_product 로" 가 FORCE INDEX 아래에서도 허용된다.</li>
     *   <li>WHERE 의 EXISTS(색상 · 용량 필터 · 증정품 후보)는 세미조인으로 바꾸지 않는다({@code NO_SEMIJOIN}) — 바꾸면 옵션 표가 맨 앞에 와 인덱스
     *       힌트가 소용없다. 대가: 통계가 맞고 다른 상품이 아주 많으면 세미조인이 더 빠를 수 있다(리뷰 실측: 다른 카테고리 5000 상품에서 18ms 대 6ms).</li>
     * </ul>
     * 통계가 실제와 다르면(데이터를 한꺼번에 넣은 직후 — 자동 재계산으로 영구 통계가 바뀐 뒤에도 계획은 ANALYZE TABLE 전까지 그대로였다) 옵티마이저가
     * 상품마다 옵션 표나 재고 표 전체를 훑는다. 실측(MySQL 8.4.11, 한 카테고리 상품 300 · 옵션 600, 목록 20개 + 건수): 데이터를 넣은 직후 힌트 없이
     * 120~143ms → 힌트로 5.6~6.3ms, ANALYZE TABLE 뒤에는 둘 다 4.5~5.8ms. 시험이 틀린 통계 세 모양(구분값만 · 옵션 · 재고 행 수까지 · 재고 표만)에서
     * 옵션 · 재고 접근이 전부 ref/eq_ref 인지 본다. 위치별로 잰 것(READY 가 재고 표를 읽던 때 포함): 필터와 증정품의 NO_SEMIJOIN · 칩의 인덱스는 하나씩 빼면 시험이
     * 잡는다. 재고 쪽의 JOIN_ORDER 와 재고 PRIMARY 는 둘 다 있어야 한다 — 구분값 0 에 재고 표 행 수까지 0 인 모양에서 JOIN_ORDER 만이면 품절이
     * 재고 표를 다 읽고(16ms), 재고 PRIMARY 만이면 품절 · 증정품이 재고부터 읽는다(36 · 28ms, 둘 다면 5.6 · 1.1ms — 리뷰 실측). 시험에 그 모양이
     * 있어 하나씩 빼면 잡힌다. 관리자 옵션 수(oc)는 어느 모양에서도 힌트 없이 ref 였다 — 같은 꼴이라 같은 힌트를 건다(일관성).
     * <p>인덱스 이름은 마이그레이션의 이름과 맞아야 한다. 힌트 이름은 접두사로도 맞는다(리뷰 실측: uq_option_product_id 로 바꿔도 오류 없이 걸린다) —
     * 이름이 바뀌면 오류(1176)가 날 수도, 다른 인덱스에 조용히 걸릴 수도 있다. 이 표에 커버링 인덱스(예: product_id · status · price)를 더하면 이
     * 힌트가 그 인덱스를 막으니 같이 바꾼다.
     */
    private static final String BY_PRODUCT = "FORCE INDEX (uq_option_product)";
    private static final String INVENTORY_BY_OPTION = "FORCE INDEX (PRIMARY)";

    /**
     * 판매 방식별 준비 — 등록 이벤트를 받은 서비스가 자기 표에 행을 만들었는가. 사전예약은 preorder 회차 행, 일반은 order 가 재고 행을 만들었다고
     * 알린 시각(products.stock_ready_at — InStockProductReadyHandler. order 의 재고 표는 읽지 않는다). products 를 p 로 둔 문장에 넣는다.
     */
    static final String READY = """
            (CASE WHEN p.sale_mode = 'PREORDER'
                  THEN EXISTS (SELECT 1 FROM preorder_campaigns rc WHERE rc.product_id = p.id)
                  ELSE p.stock_ready_at IS NOT NULL END)""";

    private static final String FROM_VISIBLE = """
              FROM products p
              LEFT JOIN preorder_campaigns c ON c.product_id = p.id
             WHERE p.visible = 1
               AND p.status = 'ACTIVE'
               AND (p.sale_mode = 'IN_STOCK' OR (c.opens_at <= :now AND c.closes_at > :now))
               AND\s""" + READY + "\n";

    /**
     * 판매 중 옵션의 최저 정가. 할인가는 정가에 대해 줄지 않으므로(같은 상품은 할인율이 하나) 최저 정가에 할인을 건 값이 최저 할인가다 —
     * 옵션마다 할인가를 계산해 MIN 하지 않는다.
     */
    private static final String MIN_LIST_PRICE = """
            (SELECT MIN(o.price) FROM product_options o %s
                     WHERE o.product_id = p.id AND o.status = 'ACTIVE')""".formatted(BY_PRODUCT);

    private static final String SELECT_ITEMS = """
            SELECT p.id, p.sale_mode, p.title, p.brand, p.model_name, p.status, p.discount_rate, c.opens_at, c.closes_at,
                   %1$s AS min_list_price,
                   %2$s AS min_price,
                   EXISTS (SELECT 1 FROM product_options po %3$s
                            WHERE po.product_id = p.id AND po.status = 'ACTIVE') AS sellable,
                   p.thumbnail_url AS image_url,
                   CASE WHEN p.sale_mode = 'IN_STOCK' AND NOT EXISTS (
                            SELECT /*+ JOIN_ORDER(o, inv) */ 1 FROM product_options o %3$s
                              JOIN option_inventories inv %4$s ON inv.option_id = o.id
                             WHERE o.product_id = p.id AND o.status = 'ACTIVE'
                               AND inv.stock_total - inv.stock_reserved - inv.stock_sold > 0)
                        THEN 1 ELSE 0 END AS sold_out
            """.formatted(MIN_LIST_PRICE, Discount.sql(MIN_LIST_PRICE, "p.discount_rate"), BY_PRODUCT, INVENTORY_BY_OPTION);

    /**
     * 색상칩에 쓰는 칸 — 옵션 문서와, 판매 중 옵션이 고른 색상(정규화값, 목록의 color 필터가 보는 값). 칩을 내보내는 회원 목록 · 홍보 상세만,
     * 쪽을 고른 **뒤에** 그 상품 id 로 따로 읽는다({@link #withColors}) — 목록 문장에 넣으면 정렬 전에 후보 행 전부에서 계산되고(실측: 후보 300행에
     * 56ms → 72ms, 정렬 방식과 상관없이) 행마다 수 KB 인 옵션 문서가 정렬 버퍼에 실린다.
     */
    private static final String CHIP_COLUMNS = """
            SELECT p.id, p.options,
                   (SELECT JSON_ARRAYAGG(sc.filter_attributes->>'$.color') FROM product_options sc %s
                     WHERE sc.product_id = p.id AND sc.status = 'ACTIVE') AS sellable_colors
              FROM products p
             WHERE p.id IN (:ids)
            """.formatted(BY_PRODUCT);

    /** 관리자 목록 — 노출 규칙 없이 전부. 삭제한 상품만 뺀다. */
    private static final String FROM_ALL = """
              FROM products p
              LEFT JOIN preorder_campaigns c ON c.product_id = p.id
             WHERE p.deleted_at IS NULL
            """;

    private static final String ADMIN_COLUMNS = """
            ,
                   p.visible, %s AS ready,
                   (SELECT COUNT(*) FROM product_options oc %s WHERE oc.product_id = p.id) AS option_count
            """.formatted(READY, BY_PRODUCT);

    /** 건수는 products 와 필터만 본다 — 회차는 건수에 영향이 없다(product_id 가 PRIMARY KEY 라 행이 안 는다). */
    private static final String COUNT_FROM = """
              FROM products p
             WHERE p.deleted_at IS NULL
            """;

    private static final Logger log = LoggerFactory.getLogger(ProductListingQueryRepository.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        return withColors(jdbc.query(sql.toString(), params, (rs, rowNum) -> toItem(rs, now)));
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
        // µs 로 자른다 — datetime(6) 바인딩은 µs 아래를 반올림해(드라이버) 접수 단계(PreorderSaleStatus, 자르지 않음)와 1µs 어긋날 수 있다
        return new MapSqlParameterSource("now", utc(now.truncatedTo(ChronoUnit.MICROS)));
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
            // 세미조인으로 바꾸면 옵션 표부터 전부 읽는 계획이 될 수 있다(통계가 틀릴 때 실측 — 맨 앞 표에는 인덱스 힌트가 소용없다). 상품마다 인덱스로 찾게 둔다
            sql.append(" AND EXISTS (SELECT /*+ NO_SEMIJOIN() */ 1 FROM product_options o ").append(BY_PRODUCT).append(" WHERE o.product_id = p.id AND o.status = 'ACTIVE'");
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
     * 판매 준비({@link #READY} — stock_ready_at)는 보지 않는다: 재고 행은 있는데 판매 준비 이벤트가 아직 닿지 않은 상품은 목록에는 없고 후보에는
     * 나온다(관리자가 증정품을 고르는 화면이라 재고 행이 기준이다).
     */
    private static final String GIFT_OPTION = """
            SELECT /*+ NO_SEMIJOIN() JOIN_ORDER(go, gi) */ 1 FROM product_options go %s JOIN option_inventories gi %s ON gi.option_id = go.id
             WHERE go.product_id = p.id AND go.status = 'ACTIVE'""".formatted(BY_PRODUCT, INVENTORY_BY_OPTION);

    /** 증정품 후보 상품 — 일반 판매 · 판매 중이고 후보 옵션이 하나 이상. 공개 여부는 보지 않는다. 삭제한 상품은 뺀다. */
    private static final String GIFT_PRODUCTS = """
              FROM products p
             WHERE p.sale_mode = 'IN_STOCK' AND p.status = 'ACTIVE' AND p.deleted_at IS NULL
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

    /**
     * 홍보 상품 카드 — 목록과 같은 칸. 공개 · 판매 중(ACTIVE) · 판매 방식별 준비가 끝난 상품만 싣는다 — 판매 중지 · 회차 취소(PAUSED)는 뺀다
     * (2026-10-14 결정 — 취소 직후 preorder 가 회차를 닫기 전까지 "접수 중" 으로 보이지 않게). 마감 뒤 시간 · 일반 전환과는 상관없이 싣는다(홍보 페이지는
     * 마감 뒤에도 "일반 구매" 로 상품을 보인다 — 일반 전환한 상품은 판매 중이다). 순서는 ids 순서, 빠진 상품은 건너뛴다.
     *
     * @param withColors 색상칩까지 싣는다(홍보 상세). false 면 colors 는 빈 목록(홍보 목록은 회차 시각만 쓴다)
     */
    public List<ProductListItem> findCards(List<UUID> ids, Instant now, boolean withColors) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String sql = SELECT_ITEMS + """
                  FROM products p
                  LEFT JOIN preorder_campaigns c ON c.product_id = p.id
                 WHERE p.id IN (:ids) AND p.visible = 1 AND p.status = 'ACTIVE' AND
                """ + READY;
        Map<UUID, ProductListItem> byId = new HashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", ids.stream().map(UuidBinary::toBytes).toList()),
                (rs, rowNum) -> toItem(rs, now)).forEach(item -> byId.put(item.productId(), item));
        List<ProductListItem> cards = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return withColors ? withColors(cards) : cards;
    }

    /**
     * 목록 카드를 고른 상품만 — 회원 목록과 같은 노출 규칙(공개 · 판매 중 · 준비 끝 · 사전예약은 접수 중)과 같은 칸(색상칩 포함). 순서는 ids 순서,
     * 규칙에 맞지 않는 상품은 빠진다. 조회수 랭킹이 쓴다.
     */
    public List<ProductListItem> findListedCards(List<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = baseParams(now).addValue("ids", ids.stream().map(UuidBinary::toBytes).toList());
        Map<UUID, ProductListItem> byId = new HashMap<>();
        jdbc.query(SELECT_ITEMS + FROM_VISIBLE + " AND p.id IN (:ids)", params, (rs, rowNum) -> toItem(rs, now))
                .forEach(item -> byId.put(item.productId(), item));
        return withColors(ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList());
    }

    /** 고른 상품들에 색상칩을 붙인다 — 칩 칸({@link #CHIP_COLUMNS})을 그 상품 id 로 한 번에 읽는다. 순서는 그대로. */
    private List<ProductListItem> withColors(List<ProductListItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        Map<UUID, List<ProductOptions.ColorChip>> chips = new HashMap<>();
        jdbc.query(CHIP_COLUMNS, new MapSqlParameterSource("ids", items.stream().map(item -> UuidBinary.toBytes(item.productId())).toList()),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID productId = UuidBinary.fromBytes(rs.getBytes("id"));
                    chips.put(productId, colorChips(productId, rs.getString("options"), rs.getString("sellable_colors")));
                });
        return items.stream().map(item -> item.withColors(chips.getOrDefault(item.productId(), List.of()))).toList();
    }

    /**
     * 홍보에 묶을 수 있는 상품 — 회차가 취소되지 않은 사전예약 상품 중 다른 홍보에 묶이지 않은 것. promotionId 를 주면(수정) 그 홍보에 이미 묶인
     * 상품도 판매 방식과 상관없이 싣는다(일반으로 바꾼 상품도 수정 화면에서 풀 수 있게). 공개 여부 · 준비와는 상관없다(관리자 화면). 최신 등록순.
     * 삭제한 상품은 어느 쪽으로도 싣지 않는다.
     */
    public OffsetPage<LinkableRow> findLinkable(UUID promotionId, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource("promotionId", promotionId == null ? null : UuidBinary.toBytes(promotionId));
        String where = """
                 WHERE p.deleted_at IS NULL
                   AND ((p.sale_mode = 'PREORDER' AND p.campaign_canceled_at IS NULL
                         AND NOT EXISTS (SELECT 1 FROM promotion_products other WHERE other.product_id = p.id
                                          AND (:promotionId IS NULL OR other.promotion_id <> :promotionId)))
                        OR EXISTS (SELECT 1 FROM promotion_products mine WHERE mine.product_id = p.id AND mine.promotion_id = :promotionId))
                """;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM products p" + where, params, Long.class);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<LinkableRow> rows = jdbc.query("""
                SELECT p.id, p.title, p.sale_mode, c.opens_at
                  FROM products p
                  LEFT JOIN preorder_campaigns c ON c.product_id = p.id
                """ + where + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset", params,
                (rs, rowNum) -> new LinkableRow(UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("title"),
                        SaleMode.valueOf(rs.getString("sale_mode")), instant(rs, "opens_at")));
        return OffsetPage.of(rows, page, size, total == null ? 0 : total);
    }

    /** @param opensAt 회차 오픈 시각. 회차 행이 없으면(등록 이벤트 처리 전) null */
    public record LinkableRow(UUID productId, String title, SaleMode saleMode, Instant opensAt) {
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

    /** 색상칩은 비운다 — 칩을 내보내는 곳은 {@link #withColors} 로 붙인다. */
    private static ProductListItem toItem(ResultSet rs, Instant now) throws SQLException {
        UUID productId = UuidBinary.fromBytes(rs.getBytes("id"));
        SaleMode saleMode = SaleMode.valueOf(rs.getString("sale_mode"));
        // 회차 시각은 사전예약만 — 일반 전환한 상품에도 preorder 회차 행이 남아 있어 그대로 싣지 않는다
        Instant opensAt = saleMode == SaleMode.PREORDER ? instant(rs, "opens_at") : null;
        Instant closesAt = saleMode == SaleMode.PREORDER ? instant(rs, "closes_at") : null;
        BigDecimal minPrice = rs.getBigDecimal("min_price");
        BigDecimal minListPrice = rs.getBigDecimal("min_list_price");
        return new ProductListItem(productId, saleMode, rs.getString("title"),
                Brand.valueOf(rs.getString("brand")), rs.getString("model_name"), rs.getString("image_url"),
                List.of(),
                SaleStatus.valueOf(rs.getString("status")), minPrice, minListPrice, rs.getInt("discount_rate"), rs.getBoolean("sellable"), rs.getBoolean("sold_out"),
                null, opensAt, closesAt).at(now);   // 접수 단계는 캐시에서 꺼낼 때와 같은 규칙(ProductListItem#at)으로
    }

    /**
     * 색상칩. 옵션 문서를 읽지 못하면(모양이 틀린 행) 그 상품만 칩을 비우고 경고한다 — 예외를 그대로 두면 그 행이 든 목록 · 홍보 전체가 500 이다
     * (실측). 옵션 문서는 앱이 쓰지만 중첩 칸에 원시형을 더하면 옛 행이 이 모양이 된다(ProductOptions.Warranty).
     */
    private static List<ProductOptions.ColorChip> colorChips(UUID productId, String options, String sellableColors) {
        try {
            List<String> sellable = sellableColors == null ? List.of()
                    : JSON.readValue(sellableColors, new TypeReference<List<String>>() { }).stream().filter(java.util.Objects::nonNull).toList();
            return ProductOptions.parse(options).colorChips(new HashSet<>(sellable));
        } catch (RuntimeException e) {
            log.warn("옵션 문서를 읽지 못해 색상칩을 비운다 productId={}: {}", productId, e.toString());
            return List.of();
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
