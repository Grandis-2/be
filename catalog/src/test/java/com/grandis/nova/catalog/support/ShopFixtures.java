package com.grandis.nova.catalog.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;

/**
 * 테스트 데이터. 마이그레이션 이전 칼럼만으로 INSERT 한다 — 다른 모듈(preorder · order)의 픽스처가 그렇게 넣으므로
 * 새 칼럼의 DEFAULT 가 그 행들을 유효하게 지키는지도 이 픽스처가 같이 검증한다.
 *
 * 매번 새 행을 만들고 지우지 않는다. 유일 칸은 UUID 로 채워 테스트끼리 겹치지 않으므로
 * 커밋하는 테스트와 롤백하는 테스트가 같은 컨테이너를 순서 상관없이 쓸 수 있다.
 */
public class ShopFixtures {

    public static final BigDecimal OPTION_PRICE = new BigDecimal("1250000");

    private final JdbcTemplate jdbcTemplate;

    public ShopFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 새 상위 카테고리. 다른 모듈 픽스처와 같은 모양(parent 없이). */
    public Long category() {
        return category(unique(), "스마트폰");
    }

    /** code 는 유일해야 한다 — 순서를 가르고 싶으면 접두사 + unique() 로 만든다. */
    public Long category(String code, String name) {
        return insert("""
                INSERT INTO categories (code, name, created_at, updated_at)
                VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, code, name);
    }

    /** 상위 아래 하위 카테고리. */
    public Long childCategory(Long parentId, String name) {
        return childCategory(parentId, unique(), name);
    }

    public Long childCategory(Long parentId, String code, String name) {
        return insert("""
                INSERT INTO categories (code, name, parent_id, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, code, name, parentId);
    }

    public Long product(String saleMode, String status) {
        return product(category(), saleMode, status);
    }

    public Long product(Long categoryId, String saleMode, String status) {
        return insert("""
                INSERT INTO products (category_id, sale_mode, title, status, created_at, updated_at)
                VALUES (?, ?, 'Nova 1', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, categoryId, saleMode, status);
    }

    public Long option(Long productId, String status) {
        return insert("""
                INSERT INTO product_options (product_id, sku, title, price, status, created_at, updated_at)
                VALUES (?, ?, '블랙 / 256GB', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, unique(), OPTION_PRICE, status);
    }

    public Long optionWithCombination(Long productId, String combinationKey) {
        return insert("""
                INSERT INTO product_options (product_id, sku, title, price, combination_key, status, created_at, updated_at)
                VALUES (?, ?, '블랙 / 256GB', ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, unique(), OPTION_PRICE, combinationKey);
    }

    public Long axis(Long productId, String axisKey, int position) {
        return insert("""
                INSERT INTO product_option_axes (product_id, axis_key, label, position, created_at, updated_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, axisKey, axisKey, position);
    }

    public Long value(Long axisId, String normalizedValue, int position) {
        return insert("""
                INSERT INTO product_option_values (axis_id, value, normalized_value, surcharge, position, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, axisId, normalizedValue, normalizedValue, position);
    }

    public void selection(Long productId, Long optionId, Long axisId, Long valueId) {
        jdbcTemplate.update("""
                INSERT INTO product_option_selections (product_id, option_id, axis_id, value_id)
                VALUES (?, ?, ?, ?)
                """, productId, optionId, axisId, valueId);
    }

    public Long image(Long productId, String kind, String bundleKey, int position, boolean primary) {
        return insert("""
                INSERT INTO product_images (product_id, kind, bundle_key, position, url, is_primary, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'https://img.example/x.jpg', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, kind, bundleKey, position, primary);
    }

    public void registration(Long productId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO product_registrations (product_id, idempotency_key, request_hash, requested_visible, created_at, updated_at)
                VALUES (?, ?, UNHEX(REPEAT('ab', 32)), 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, idempotencyKey);
    }

    public static String unique() {
        return UUID.randomUUID().toString();
    }

    private Long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("생성된 키가 없습니다: " + sql);
        }
        return key.longValue();
    }
}
