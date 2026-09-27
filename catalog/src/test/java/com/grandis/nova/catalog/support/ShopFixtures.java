package com.grandis.nova.catalog.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    /** 제목 · 검색 키워드까지 지정한 상품. 목록 시험이 자기 상품만 골라내는 데 쓴다. */
    public Long product(Long categoryId, String saleMode, String status, String title, String tags) {
        return insert("""
                INSERT INTO products (category_id, sale_mode, title, tags, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, categoryId, saleMode, title, tags, status);
    }

    /** 등록이 끝난 상품 — 노출 규칙의 앞 조건. */
    public void completeRegistration(Long productId) {
        registration(productId, unique());
        jdbcTemplate.update("UPDATE product_registrations SET completed_at = UTC_TIMESTAMP(6) WHERE product_id = ?", productId);
    }

    /** preorder 소유 표. catalog 코드는 쓰지 않고 시험 데이터로만 넣는다. */
    public void campaign(Long productId, Instant opensAt, Instant closesAt) {
        jdbcTemplate.update("""
                INSERT INTO preorder_campaigns (product_id, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, utc(opensAt), utc(closesAt));
    }

    /** order 소유 표. 가용 = total - reserved - sold. */
    public void inventory(Long optionId, int total, int reserved, int sold) {
        jdbcTemplate.update("""
                INSERT INTO option_inventories (option_id, stock_total, stock_reserved, stock_sold, updated_at)
                VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, optionId, total, reserved, sold);
    }

    /** 필터 · 표시 JSON 까지 넣은 옵션. */
    public Long optionWithAttributes(Long productId, String status, BigDecimal price, String title,
                                     String filterJson, String displayJson) {
        return insert("""
                INSERT INTO product_options (product_id, sku, title, price, filter_attributes, display_attributes, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, unique(), title, price, filterJson, displayJson, status);
    }

    public Long option(Long productId, String status, BigDecimal price) {
        return insert("""
                INSERT INTO product_options (product_id, sku, title, price, status, created_at, updated_at)
                VALUES (?, ?, '옵션', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, unique(), price, status);
    }

    /** DB 는 UTC 벽시계 시각을 담는다. Timestamp 로 넘기면 JVM 시간대로 바뀌어 들어간다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
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
        return value(axisId, normalizedValue, normalizedValue, position);
    }

    /** 표시값과 정규화값을 갈라 넣는다 — 둘을 같게 넣으면 어느 칸으로 비교하는지 시험이 못 가른다. */
    public Long value(Long axisId, String displayValue, String normalizedValue, int position) {
        return insert("""
                INSERT INTO product_option_values (axis_id, value, normalized_value, surcharge, position, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, axisId, displayValue, normalizedValue, position);
    }

    public void selection(Long productId, Long optionId, Long axisId, Long valueId) {
        jdbcTemplate.update("""
                INSERT INTO product_option_selections (product_id, option_id, axis_id, value_id)
                VALUES (?, ?, ?, ?)
                """, productId, optionId, axisId, valueId);
    }

    public Long image(Long productId, String kind, String bundleKey, int position, boolean primary) {
        return image(productId, kind, bundleKey, position, primary, "https://img.example/x.jpg");
    }

    public Long image(Long productId, String kind, String bundleKey, int position, boolean primary, String url) {
        return insert("""
                INSERT INTO product_images (product_id, kind, bundle_key, position, url, is_primary, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, kind, bundleKey, position, url, primary);
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
