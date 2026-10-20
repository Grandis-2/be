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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 상품 목록 · 검색 · 상세 · 관리자 목록의 읽기 전용 저장소. catalog 의 표만 읽는다 — 다른 서비스의 값은 API 로 받는다: 사전예약 회차(행의 유무 ·
 * 오픈 · 마감 시각)는 preorder(PreorderCampaigns), 품절 · 남은 수량은 order 재고(SoldOutMarker · StockReader). 이 저장소의 카드는 회차 시각을 비워
 * 내보내고(호출자가 {@link ProductListItem#withCampaign} 으로 채운다), 일반 상품의 판매 준비는 order 가 재고 행을 만들었다고 알린 이벤트로 채운
 * products.stock_ready_at 이다({@link #READY}).
 * 노출 조건(판매 방식별 준비 · 공개 · 판매 중 · 사전예약은 접수 중일 때만)이 페이징 조건이라 쿼리 안에 있어야 한다 — 사전예약의 "접수 중" 은 호출자가
 * preorder 의 회차로 가른 상품 id 를 받아 쿼리에 넣는다(HTTP 결과로 페이지를 거르면 페이지가 깨진다).
 * 판매 중(ACTIVE) 옵션이 하나도 없는 상품(전부 판매 중지)은 목록에 남기고 sellable=false 로 알린다 — 화면이 "판매 중지" 를 그린다.
 * 옵션이 아예 없는 상품은 등록으로 생기지 않는다(조합 0 은 400, 옵션 삭제 경로 없음). 일반 상품이면 재고 행을 만들 옵션이 없어 준비될 수 없다
 * (사용자 결정 2026-09-27). 숨기지 않는다.
 *
 * id 는 BINARY(16) 이라 {@link UuidBinary} 로 바인딩하고 읽는다.
 * 회원 목록의 정렬은 {@link ProductSort}(최신순 · 가격순), 관리자 목록은 최신순(등록 시각 내림차순, 같으면 id 내림차순) 고정이다.
 */
@Repository
public class ProductListingQueryRepository {

    /**
     * 상품마다 그 상품의 옵션을 찾는 서브쿼리의 계획을 통계와 상관없이 고정한다.
     * <ul>
     *   <li>옵션은 {@link #BY_PRODUCT} — (product_id, id) 로 찾는다.</li>
     *   <li>WHERE 의 EXISTS(색상 · 용량 필터 · 증정품 후보)는 세미조인으로 바꾸지 않는다({@code NO_SEMIJOIN}) — 바꾸면 옵션 표가 맨 앞에 와 인덱스
     *       힌트가 소용없다. 대가: 통계가 맞고 다른 상품이 아주 많으면 세미조인이 더 빠를 수 있다(리뷰 실측: 다른 카테고리 5000 상품에서 18ms 대 6ms).</li>
     * </ul>
     * 통계가 실제와 다르면(데이터를 한꺼번에 넣은 직후 — 자동 재계산으로 영구 통계가 바뀐 뒤에도 계획은 ANALYZE TABLE 전까지 그대로였다) 옵티마이저가
     * 상품마다 옵션 표 전체를 훑는다. 실측(MySQL 8.4.11, 한 카테고리 상품 300 · 옵션 600, 목록 20개 + 건수 — 품절 · 판매 준비가 아직 재고 표를 읽던
     * 때): 데이터를 넣은 직후 힌트 없이 120~143ms → 힌트로 5.6~6.3ms, ANALYZE TABLE 뒤에는 둘 다 4.5~5.8ms. 시험이 틀린 통계 모양에서 옵션 접근이
     * ref/eq_ref 인지 본다. 필터와 증정품의 NO_SEMIJOIN · 칩의 인덱스는 하나씩 빼면 시험이 잡는다(그때 잰 것). 관리자 옵션 수(oc)는 어느 모양에서도
     * 힌트 없이 ref 였다 — 같은 꼴이라 같은 힌트를 건다(일관성).
     * <p>인덱스 이름은 마이그레이션의 이름과 맞아야 한다. 힌트 이름은 접두사로도 맞는다(리뷰 실측: uq_option_product_id 로 바꿔도 오류 없이 걸린다) —
     * 이름이 바뀌면 오류(1176)가 날 수도, 다른 인덱스에 조용히 걸릴 수도 있다. 이 표에 커버링 인덱스(예: product_id · status · price)를 더하면 이
     * 힌트가 그 인덱스를 막으니 같이 바꾼다.
     */
    private static final String BY_PRODUCT = "FORCE INDEX (uq_option_product)";

    /**
     * DB 로 아는 판매 방식별 준비 — 일반은 order 가 재고 행을 만들었다고 알린 시각(products.stock_ready_at — InStockProductReadyHandler. order 의
     * 재고 표는 읽지 않는다). 사전예약의 준비(preorder 회차 행이 있는가)는 catalog DB 에 없어 이 식에서는 참이다 — 회차를 보는 자리는 preorder 에
     * 묻고(PreorderCampaigns), 내부 상품 조회(1.3 · /internal/options)는 묻지 않고 참으로 싣는다(contracts/order-internal.md). products 를 p 로 둔 문장에 넣는다.
     */
    public static final String READY = "(p.sale_mode = 'PREORDER' OR p.stock_ready_at IS NOT NULL)";

    /**
     * 판매 중 옵션의 최저 정가. 할인가는 정가에 대해 줄지 않으므로(같은 상품은 할인율이 하나) 최저 정가에 할인을 건 값이 최저 할인가다 —
     * 옵션마다 할인가를 계산해 MIN 하지 않는다.
     */
    private static final String MIN_LIST_PRICE = """
            (SELECT MIN(o.price) FROM product_options o %s
                     WHERE o.product_id = p.id AND o.status = 'ACTIVE')""".formatted(BY_PRODUCT);

    private static final String SELECT_ITEMS = """
            SELECT p.id, p.sale_mode, p.title, p.brand, p.model_name, p.status, p.discount_rate,
                   %1$s AS min_list_price,
                   %2$s AS min_price,
                   EXISTS (SELECT 1 FROM product_options po %3$s
                            WHERE po.product_id = p.id AND po.status = 'ACTIVE') AS sellable,
                   p.thumbnail_url AS image_url
            """.formatted(MIN_LIST_PRICE, Discount.sql(MIN_LIST_PRICE, "p.discount_rate"), BY_PRODUCT);

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
             WHERE p.deleted_at IS NULL
            """;

    private static final String ADMIN_COLUMNS = """
            ,
                   p.visible, %s AS ready,
                   (SELECT COUNT(*) FROM product_options oc %s WHERE oc.product_id = p.id) AS option_count
            """.formatted(READY, BY_PRODUCT);

    /** 관리자 건수는 products 와 필터만 본다. */
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

    /** @param accepting 지금 접수 중인 사전예약 상품 id(preorder 회차로 호출자가 가른다). 여기 없는 사전예약 상품은 세지 않는다 */
    public long count(ProductListFilter filter, Collection<UUID> accepting) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(fromVisible(accepting, params));
        appendFilters(sql, params, filter);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 한 쪽과 전체 건수를 한 문장으로 읽는다 — 건수 한 행에 그 쪽을 LATERAL 로 붙인다. 한 문장이라 READ COMMITTED 에서도 건수와 쪽이 같은 시점이다
     * (트랜잭션을 열지 않는다 — 운영 DB 왕복이 수십 ms 라 읽기 전용 REPEATABLE READ 트랜잭션의 준비 · 정리 문장 8개가 쿼리보다 비쌌다).
     * 쪽이 범위 밖이어도 건수 행은 남아 total 이 그대로다(COUNT(*) OVER() 는 빈 쪽에서 total 을 잃는다). 실측(MySQL 8.4.11, 상품 3,000 중 검색 일치 300,
     * 20개 쪽): 두 문장 3.7~6.4ms, 이 모양 3.2~5.9ms. 색상칩은 쪽을 고른 뒤 다음 문장으로 읽는다({@link #withColors}) — 쪽과 시점이 다를 수
     * 있다(그사이 판매 중지된 옵션이면 카드의 최저가 · 판매 가능은 앞 값, 칩은 뒤 값). 표시 칸이고 목록 캐시도 지난 값을 주므로 받아들인다.
     *
     * @param accepting {@link #count} 와 같다. 카드의 회차 시각은 비어 있다
     */
    public OffsetPage<ProductListItem> findPage(ProductListFilter filter, ProductSort sort, Collection<UUID> accepting, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(fromVisible(accepting, params));
        appendFilters(where, params, filter);
        String sql = "SELECT c.total, x.* FROM (SELECT COUNT(*) AS total" + where + ") c"
                + " LEFT JOIN LATERAL (" + SELECT_ITEMS + ", p.created_at" + where + sort.orderBy() + " LIMIT :limit OFFSET :offset) x ON TRUE"
                + sort.pageOrderBy();
        params.addValue("limit", size).addValue("offset", (long) page * size);
        long[] total = {0};
        List<ProductListItem> items = new ArrayList<>();
        jdbc.query(sql, params, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            total[0] = rs.getLong("total");
            if (rs.getBytes("id") != null) {   // 빈 쪽이면 건수 행 하나만 온다(x 의 칸은 NULL)
                items.add(toItem(rs));
            }
        });
        return OffsetPage.of(withColors(items), page, size, total[0]);
    }

    /**
     * 회원에게 보이는 상품 — 공개 · 판매 중이고, 일반은 판매 준비, 사전예약은 지금 접수 중(accepting — 회차 행이 있어야 접수 중이므로 준비도 같이 끝났다).
     * 빈 id 목록은 IN () 이 문법 오류라 조건을 거짓으로 둔다.
     */
    private static String fromVisible(Collection<UUID> accepting, MapSqlParameterSource params) {
        String preorder = "FALSE";
        if (!accepting.isEmpty()) {
            preorder = "p.sale_mode = 'PREORDER' AND p.id IN (:accepting)";
            params.addValue("accepting", accepting.stream().map(UuidBinary::toBytes).toList());
        }
        return """
                  FROM products p
                 WHERE p.visible = 1
                   AND p.status = 'ACTIVE'
                   AND ((p.sale_mode = 'IN_STOCK' AND p.stock_ready_at IS NOT NULL) OR (%s))
                """.formatted(preorder);
    }

    public long countForAdmin(AdminProductListFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(COUNT_FROM);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendAdminFilters(sql, params, filter);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 회원 목록과 같은 열(최저가 · 썸네일 · 판매 가능 · 품절)에 공개 여부 · 판매 방식별 준비 · 옵션 수를 더해 읽는다. 사전예약의 회차 시각 · 준비는
     * 비어(준비는 참) 있다 — 호출자가 preorder 에 물어 채운다({@link AdminProductListItem#withCampaign}).
     */
    public List<AdminProductListItem> findForAdmin(AdminProductListFilter filter, int page, int size) {
        StringBuilder sql = new StringBuilder(SELECT_ITEMS.stripTrailing()).append(ADMIN_COLUMNS).append(FROM_ALL);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendAdminFilters(sql, params, filter);
        sql.append(" ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> AdminProductListItem.of(toItem(rs),
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
     * 공개 여부 · 판매 상태 · DB 로 아는 판매 방식별 준비({@link #READY} — 사전예약은 참)를 한 문장으로 읽는다. 상품 리뷰 목록과
     * 조회수 랭킹의 노출 판정이 쓴다 — 판정에 쓰는 칸이 같은 시점이어야 해서 한 문장이다. 상품이 없으면 비어 있다.
     */
    public Optional<Exposure> findExposure(UUID productId) {
        List<Exposure> rows = jdbc.query("SELECT p.sale_mode, p.visible, p.status, " + READY + " AS ready FROM products p WHERE p.id = :productId",
                new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)), (rs, rowNum) -> exposure(rs));
        return rows.stream().findFirst();
    }

    private static Exposure exposure(ResultSet rs) throws SQLException {
        return new Exposure(SaleMode.valueOf(rs.getString("sale_mode")), rs.getBoolean("visible"), SaleStatus.valueOf(rs.getString("status")),
                rs.getBoolean("ready"));
    }

    /** 한 문장으로 읽은 노출 판정 재료. @param ready DB 로 아는 판매 방식별 준비({@link #READY}) — 사전예약은 참이다 */
    public record Exposure(SaleMode saleMode, boolean visible, SaleStatus status, boolean ready) {
    }

    /** 일반 상품의 판매 준비 — order 의 판매 준비 이벤트를 받았다(products.stock_ready_at). 상품이 없으면 false. 사전예약의 준비는 preorder 에 묻는다. */
    public boolean isStockReady(UUID productId) {
        List<Boolean> rows = jdbc.query("SELECT p.stock_ready_at IS NOT NULL AS ready FROM products p WHERE p.id = :productId",
                new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)), (rs, rowNum) -> rs.getBoolean("ready"));
        return !rows.isEmpty() && rows.getFirst();
    }

    /**
     * 증정품 후보 옵션 — 판매 중인 옵션. 재고 행이 있는지(남은 수량)는 쪽을 고른 뒤 order 에 묻는다({@link ProductListingService#listGiftCandidates}).
     * 상품 쪽 조건(일반 판매 · 판매 중 · 판매 준비)은 {@link #GIFT_PRODUCTS} 가 건다.
     */
    private static final String GIFT_OPTION = """
            SELECT /*+ NO_SEMIJOIN() */ 1 FROM product_options go %s
             WHERE go.product_id = p.id AND go.status = 'ACTIVE'""".formatted(BY_PRODUCT);

    /**
     * 증정품 후보 상품 — 일반 판매 · 판매 중 · 판매 준비(order 가 재고 행을 만들었다고 알림 — stock_ready_at)이고 판매 중 옵션이 하나 이상. 공개 여부는
     * 보지 않는다. 삭제한 상품은 뺀다.
     * 판매 준비를 같이 보는 것은 회차 만들기(order)가 내부 옵션 조회의 registrationCompleted(= 이 판매 준비)로 막기 때문이다 — 빼면 재고 행은 있는데
     * 판매 준비 이벤트가 아직 닿지 않은 상품이 후보에 뜨고, 골라서 만들면 order 가 400 이다.
     */
    private static final String GIFT_PRODUCTS = """
              FROM products p
             WHERE p.sale_mode = 'IN_STOCK' AND p.status = 'ACTIVE' AND p.deleted_at IS NULL AND p.stock_ready_at IS NOT NULL
               AND EXISTS (%s)
            """.formatted(GIFT_OPTION);

    public long countGiftCandidates(String q) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(GIFT_PRODUCTS);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendTextFilter(sql, params, q);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 증정품 후보 상품 한 쪽과 그 판매 중 옵션들. 상품은 관리자 목록과 같은 최신순, 옵션은 등록 순. 같은 스냅샷이 되게 REPEATABLE READ 안에서 부른다.
     * 옵션의 남은 수량은 비워 둔다(0) — 호출자가 order 에 물어 채운다(재고 행이 없는 옵션은 0 으로 남는다).
     */
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
                SELECT o.product_id, o.id, o.sku, o.title
                  FROM product_options o
                 WHERE o.product_id IN (:productIds) AND o.status = 'ACTIVE'
                 ORDER BY o.product_id, o.created_at, o.id
                """, new MapSqlParameterSource("productIds", products.stream().map(c -> UuidBinary.toBytes(c.productId())).toList()),
                rs -> {
                    options.computeIfAbsent(UuidBinary.fromBytes(rs.getBytes("product_id")), id -> new ArrayList<>())
                            .add(new GiftCandidate.Option(UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("sku"), rs.getString("title"), 0));
                });
        return products.stream()
                .map(c -> new GiftCandidate(c.productId(), c.title(), c.imageUrl(), c.visible(), List.copyOf(options.getOrDefault(c.productId(), List.of()))))
                .toList();
    }

    /**
     * 홍보 상품 카드 — 목록과 같은 칸. 공개 · 판매 중(ACTIVE) · DB 로 아는 판매 준비({@link #READY})인 상품만 싣는다 — 판매 중지 · 회차 취소(PAUSED)는 뺀다
     * (2026-10-14 결정 — 취소 직후 preorder 가 회차를 닫기 전까지 "접수 중" 으로 보이지 않게). 마감 뒤 시간 · 일반 전환과는 상관없이 싣는다(홍보 페이지는
     * 마감 뒤에도 "일반 구매" 로 상품을 보인다 — 일반 전환한 상품은 판매 중이다). 순서는 ids 순서, 빠진 상품은 건너뛴다. 사전예약의 준비(회차 행)와
     * 회차 시각은 호출자가 preorder 에 물어 거르고 채운다.
     *
     * @param withColors 색상칩까지 싣는다(홍보 상세). false 면 colors 는 빈 목록(홍보 목록은 회차 시각만 쓴다)
     */
    public List<ProductListItem> findCards(List<UUID> ids, boolean withColors) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String sql = SELECT_ITEMS + """
                  FROM products p
                 WHERE p.id IN (:ids) AND p.visible = 1 AND p.status = 'ACTIVE' AND
                """ + READY;
        Map<UUID, ProductListItem> byId = new HashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", ids.stream().map(UuidBinary::toBytes).toList()),
                (rs, rowNum) -> toItem(rs)).forEach(item -> byId.put(item.productId(), item));
        List<ProductListItem> cards = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return withColors ? withColors(cards) : cards;
    }

    /**
     * 목록 카드를 고른 상품만 — 회원 목록과 같은 노출 규칙(공개 · 판매 중 · 준비 끝 · 사전예약은 접수 중)과 같은 칸(색상칩 포함, 회차 시각은 비어 있다).
     * 순서는 ids 순서, 규칙에 맞지 않는 상품은 빠진다. 조회수 랭킹 · 배너 대상이 쓴다.
     *
     * @param accepting {@link #count} 와 같다
     */
    public List<ProductListItem> findListedCards(List<UUID> ids, Collection<UUID> accepting) {
        if (ids.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids.stream().map(UuidBinary::toBytes).toList());
        Map<UUID, ProductListItem> byId = new HashMap<>();
        jdbc.query(SELECT_ITEMS + fromVisible(accepting, params) + " AND p.id IN (:ids)", params, (rs, rowNum) -> toItem(rs))
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
                SELECT p.id, p.title, p.sale_mode
                  FROM products p
                """ + where + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset", params,
                (rs, rowNum) -> new LinkableRow(UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("title"),
                        SaleMode.valueOf(rs.getString("sale_mode"))));
        return OffsetPage.of(rows, page, size, total == null ? 0 : total);
    }

    /** 오픈 시각은 호출자가 preorder 에 묻는다. */
    public record LinkableRow(UUID productId, String title, SaleMode saleMode) {
    }

    /** 상품마다 판매 중(ACTIVE) 옵션 id — 목록 카드의 품절을 order 재고로 가를 때 쓴다({@link SoldOutMarker}). 판매 중 옵션이 없는 상품은 빠진다. */
    public Map<UUID, List<UUID>> findActiveOptionIds(Collection<UUID> productIds) {
        Map<UUID, List<UUID>> options = new HashMap<>();
        if (productIds.isEmpty()) {
            return options;
        }
        jdbc.query("SELECT o.product_id, o.id FROM product_options o " + BY_PRODUCT + " WHERE o.product_id IN (:productIds) AND o.status = 'ACTIVE'",
                new MapSqlParameterSource("productIds", productIds.stream().map(UuidBinary::toBytes).toList()),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> options
                        .computeIfAbsent(UuidBinary.fromBytes(rs.getBytes("product_id")), id -> new ArrayList<>())
                        .add(UuidBinary.fromBytes(rs.getBytes("id"))));
        return options;
    }

    /** LIKE 의 와일드카드(% _ \)를 문자로 취급한다. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 색상칩은 비운다 — 칩을 내보내는 곳은 {@link #withColors} 로 붙인다. 품절은 false 로 두고 {@link SoldOutMarker} 가 order 재고로 채운다.
     * 회차 시각 · 접수 단계도 비운다 — 호출자가 preorder 의 회차로 채운다({@link ProductListItem#withCampaign}).
     */
    private static ProductListItem toItem(ResultSet rs) throws SQLException {
        UUID productId = UuidBinary.fromBytes(rs.getBytes("id"));
        SaleMode saleMode = SaleMode.valueOf(rs.getString("sale_mode"));
        BigDecimal minPrice = rs.getBigDecimal("min_price");
        BigDecimal minListPrice = rs.getBigDecimal("min_list_price");
        return new ProductListItem(productId, saleMode, rs.getString("title"),
                Brand.valueOf(rs.getString("brand")), rs.getString("model_name"), rs.getString("image_url"),
                List.of(),
                SaleStatus.valueOf(rs.getString("status")), minPrice, minListPrice, rs.getInt("discount_rate"), rs.getBoolean("sellable"), false,
                null, null, null);
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
}
