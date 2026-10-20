package com.grandis.nova.catalog.query;

import com.grandis.nova.catalog.listing.ProductListingQueryRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 다른 서비스가 묻는 상품 · 옵션을 한 문장으로 읽는다({@link ProductOptionsQueryService}). 상품의 노출 칸(공개 · 판매 상태 · DB 로 아는 준비)과
 * 옵션(판매 상태 · 가격)을 한 문장에서 읽어 READ COMMITTED 에서도 한 시점이다 — 트랜잭션을 열지 않는다(운영 DB 왕복이 수십 ms 라 읽기 전용
 * REPEATABLE READ 트랜잭션의 준비 · 정리 문장 8개가 조회보다 비쌌다. 실측 MySQL 8.4 일반 로그: 11문장 → 1문장).
 */
@Repository
class ProductOptionsQueryRepository {

    private static final String PRODUCT_COLUMNS = "p.id AS product_id, p.title AS product_title, p.model_name, p.sale_mode, p.status AS product_status,"
            + " p.visible, " + ProductListingQueryRepository.READY + " AS ready, p.deleted_at IS NOT NULL AS deleted, p.discount_rate";

    private final NamedParameterJdbcTemplate jdbc;

    ProductOptionsQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 상품 하나와 그 옵션 전부(만든 순서). 상품이 없으면 비어 있다. 옵션이 없는 상품은 옵션 목록이 비어 있다(LEFT JOIN). */
    Optional<ProductWithOptions> findProductWithOptions(UUID productId) {
        ProductWithOptions[] product = {null};
        List<OptionRow> options = new ArrayList<>();
        jdbc.query("SELECT " + PRODUCT_COLUMNS + ", o.id AS option_id, o.sku, o.title AS option_title, o.price, o.status AS option_status"
                        + " FROM products p LEFT JOIN product_options o ON o.product_id = p.id"
                        + " WHERE p.id = :productId ORDER BY o.created_at, o.id",
                new MapSqlParameterSource("productId", UuidBinary.toBytes(productId)), (RowCallbackHandler) rs -> {
                    if (product[0] == null) {
                        product[0] = new ProductWithOptions(product(rs), options);   // 옵션 목록은 아래에서 채운다 — 돌려주기 전에 닫는다
                    }
                    if (rs.getBytes("option_id") != null) {
                        options.add(new OptionRow(UuidBinary.fromBytes(rs.getBytes("option_id")), rs.getString("sku"), rs.getString("option_title"),
                                rs.getBigDecimal("price"), SaleStatus.valueOf(rs.getString("option_status")), null));
                    }
                });
        return Optional.ofNullable(product[0]).map(found -> new ProductWithOptions(found.product(), List.copyOf(options)));
    }

    /**
     * 옵션들과 그 상품(상품 문서 포함). 없는 옵션은 빠진다. 순서는 정하지 않는다. 옵션 행마다 상품 문서가 다시 실려 오지만(호출자 상한 50개)
     * 따로 읽으면 두 문장이 되어 한 시점이 깨지므로 받아들인다 — 푸는 것은 호출자가 상품마다 한 번 한다.
     */
    List<OptionWithProduct> findOptionsWithProducts(Collection<UUID> optionIds) {
        if (optionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + PRODUCT_COLUMNS + ", p.options AS document,"
                        + " o.id AS option_id, o.sku, o.title AS option_title, o.price, o.status AS option_status, o.combination_key"
                        + " FROM product_options o JOIN products p ON p.id = o.product_id WHERE o.id IN (:optionIds)",
                new MapSqlParameterSource("optionIds", optionIds.stream().map(UuidBinary::toBytes).toList()),
                (rs, rowNum) -> new OptionWithProduct(product(rs), rs.getString("document"),
                        new OptionRow(UuidBinary.fromBytes(rs.getBytes("option_id")), rs.getString("sku"), rs.getString("option_title"),
                                rs.getBigDecimal("price"), SaleStatus.valueOf(rs.getString("option_status")), rs.getString("combination_key"))));
    }

    private static ProductRow product(ResultSet rs) throws SQLException {
        return new ProductRow(UuidBinary.fromBytes(rs.getBytes("product_id")), rs.getString("product_title"), rs.getString("model_name"),
                SaleMode.valueOf(rs.getString("sale_mode")), SaleStatus.valueOf(rs.getString("product_status")), rs.getBoolean("visible"),
                rs.getBoolean("ready"), rs.getBoolean("deleted"), rs.getInt("discount_rate"));
    }

    /** @param ready DB 로 아는 판매 방식별 준비({@link ProductListingQueryRepository#READY}) — 사전예약은 참 */
    record ProductRow(UUID id, String title, String modelName, SaleMode saleMode, SaleStatus status, boolean visible, boolean ready, boolean deleted,
                      int discountRate) {
    }

    /** @param combinationKey 옵션 하나 조회(findOptionsWithProducts)에서만 싣는다 */
    record OptionRow(UUID id, String sku, String title, BigDecimal price, SaleStatus status, String combinationKey) {
    }

    record ProductWithOptions(ProductRow product, List<OptionRow> options) {
    }

    /** @param document 상품의 옵션 문서(JSON) — 보증 · 사진을 여기서 계산한다 */
    record OptionWithProduct(ProductRow product, String document, OptionRow option) {
    }
}
