package com.grandis.nova.catalog.promotion;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 홍보에 묶인 상품(promotion_products). 순서가 있는 목록이라 바꿀 때는 그 홍보의 줄을 전부 지우고 새 순서로 넣는다 — 한 줄씩 자리를 옮기면
 * 두 상품 자리를 맞바꾸는 사이 같은 상품이 두 줄이 되어 한 상품 한 홍보(uq_promotion_product)에 걸린다. 홍보 행 잠금 아래에서 부른다.
 */
@Repository
public class PromotionProducts {

    /** 한 상품 한 홍보 유일 키 이름. */
    public static final String UNIQUE_PRODUCT = "uq_promotion_product";

    private final NamedParameterJdbcTemplate jdbc;

    public PromotionProducts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 그 홍보의 묶음을 productIds 순서로 바꾼다. 다른 홍보에 묶인 상품이 있으면 {@link #UNIQUE_PRODUCT} 위반(DataIntegrityViolationException). */
    public void replace(UUID promotionId, List<UUID> productIds) {
        byte[] promotion = UuidBinary.toBytes(promotionId);
        jdbc.update("DELETE FROM promotion_products WHERE promotion_id = :promotionId", new MapSqlParameterSource("promotionId", promotion));
        // 넣는 순서는 product_id 바이트 순(position 은 보낸 순서 그대로) — 겹친 두 요청이 같은 상품들을 반대 순서로 넣으면 유일 키 잠금을 서로 기다려
        // 교착(1213)이다(실측). 같은 순서로 넣으면 늦은 쪽이 중복(1062)으로 끝나 409 가 된다
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < productIds.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Arrays.compareUnsigned(UuidBinary.toBytes(productIds.get(a)), UuidBinary.toBytes(productIds.get(b))));
        MapSqlParameterSource[] rows = new MapSqlParameterSource[productIds.size()];
        for (int k = 0; k < order.size(); k++) {
            int position = order.get(k);
            rows[k] = new MapSqlParameterSource().addValue("promotionId", promotion).addValue("position", position)
                    .addValue("productId", UuidBinary.toBytes(productIds.get(position)));
        }
        jdbc.batchUpdate("INSERT INTO promotion_products (promotion_id, position, product_id) VALUES (:promotionId, :position, :productId)", rows);
    }

    /** 홍보마다 묶인 상품 id(순서대로). 묶인 상품이 없는 홍보는 빈 목록. */
    public Map<UUID, List<UUID>> findByPromotions(Collection<UUID> promotionIds) {
        Map<UUID, List<UUID>> byPromotion = new LinkedHashMap<>();
        promotionIds.forEach(id -> byPromotion.put(id, new ArrayList<>()));
        if (promotionIds.isEmpty()) {
            return byPromotion;
        }
        jdbc.query("SELECT promotion_id, product_id FROM promotion_products WHERE promotion_id IN (:ids) ORDER BY promotion_id, position",
                new MapSqlParameterSource("ids", promotionIds.stream().map(UuidBinary::toBytes).toList()),
                rs -> {
                    byPromotion.get(UuidBinary.fromBytes(rs.getBytes("promotion_id"))).add(UuidBinary.fromBytes(rs.getBytes("product_id")));
                });
        return byPromotion;
    }

    /** 상품마다 묶인 홍보 id. 어디에도 묶이지 않은 상품은 빠진다. */
    public Map<UUID, UUID> findOwners(Collection<UUID> productIds) {
        Map<UUID, UUID> owners = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return owners;
        }
        jdbc.query("SELECT product_id, promotion_id FROM promotion_products WHERE product_id IN (:ids)",
                new MapSqlParameterSource("ids", productIds.stream().map(UuidBinary::toBytes).toList()),
                rs -> {
                    owners.put(UuidBinary.fromBytes(rs.getBytes("product_id")), UuidBinary.fromBytes(rs.getBytes("promotion_id")));
                });
        return owners;
    }
}
