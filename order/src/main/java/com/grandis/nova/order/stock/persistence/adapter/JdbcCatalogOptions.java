package com.grandis.nova.order.stock.persistence.adapter;

import com.grandis.nova.order.stock.domain.enums.SaleMode;
import com.grandis.nova.order.stock.domain.repository.CatalogOptions;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * catalog 표를 필요한 칸만 SQL 로 읽는다. JPA 엔티티로 매핑하지 않는다 — 엔티티가 있으면 쓰기 길이 열리고,
 * catalog 가 칼럼을 더할 때 ddl-auto=validate 에 끌려간다.
 */
@Repository
class JdbcCatalogOptions implements CatalogOptions {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCatalogOptions(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SaleMode> findSaleMode(Long productId) {
        return jdbc.queryForList("SELECT sale_mode FROM products WHERE id = :id",
                        new MapSqlParameterSource("id", productId), String.class)
                .stream().findFirst().map(value -> toSaleMode(productId, value));
    }

    /** ck_product_sale_mode 가 두 값만 허용한다. 그 밖의 값은 스키마와 코드가 어긋난 것이라 원인을 밝혀 실패한다. */
    private static SaleMode toSaleMode(Long productId, String value) {
        try {
            return SaleMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("알 수 없는 판매 방식: productId=" + productId + ", sale_mode=" + value, e);
        }
    }

    @Override
    public Set<Long> findOwnedOptionIds(Long productId, Collection<Long> optionIds) {
        if (optionIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "SELECT id FROM product_options WHERE product_id = :productId AND id IN (:ids)",
                new MapSqlParameterSource("productId", productId).addValue("ids", optionIds), Long.class));
    }
}
