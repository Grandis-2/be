package com.grandis.nova.catalog;

import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 마이그레이션이 만든 제약이 설계대로 막고 열어 주는지. 엔티티를 거치지 않고 SQL 로 직접 친다 —
 * 제약은 앱 코드가 아니라 DB 가 지키는 것이고, 다른 모듈도 같은 표에 SQL 로 넣기 때문이다.
 */
@CatalogIntegrationTest
class CatalogSchemaTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Nested
    @DisplayName("카테고리")
    class Categories {

        @Test
        @DisplayName("하위는 상위를 가리키고, 상위는 parent 가 비어 있다")
        void childPointsToParent() {
            Long parentId = fixtures.category();
            Long childId = fixtures.childCategory(parentId, "삼성");

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, parent_id FROM categories WHERE id IN (?, ?) ORDER BY id", parentId, childId);
            assertThat(rows).extracting(row -> row.get("parent_id")).containsExactly(null, parentId);
        }

        @Test
        @DisplayName("하위가 있는 상위는 지울 수 없다")
        void parentWithChildrenCannotBeDeleted() {
            Long parentId = fixtures.category();
            fixtures.childCategory(parentId, "Apple");
            assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM categories WHERE id = ?", parentId))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_category_parent");
        }

        @Test
        @DisplayName("없는 상위를 가리키는 카테고리는 넣을 수 없다")
        void parentMustExist() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO categories (name, parent_id, created_at, updated_at)
                    VALUES ('x', 999999999, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_category_parent");
        }

        @Test
        @DisplayName("표시 순서는 넣지 않으면 0, 음수는 거절한다. code 칸은 없다")
        void sortOrderDefaultsToZeroAndCodeIsGone() {
            Long id = new ShopFixtures(jdbcTemplate).category();
            assertThat(jdbcTemplate.queryForObject("SELECT sort_order FROM categories WHERE id = ?", Integer.class, id)).isZero();
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE categories SET sort_order = -1 WHERE id = ?", id))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_category_sort_order");   // CHECK(3819)는 무결성 예외로 분류되지 않는다(실측)
            jdbcTemplate.update("UPDATE categories SET sort_order = 0 WHERE id = ?", id);   // 대조군 — 경계는 들어간다
            assertThat(columnExists("categories", "code")).isFalse();
        }
    }

    @Nested
    @DisplayName("기존 칼럼만으로 넣은 행")
    class LegacyInserts {

        @Test
        @DisplayName("상품은 공개 · 기본가 0 · 보증 키 없는 문서로, 보증 · 수동 가격 표시 · 표시 속성 칼럼이 없다")
        void defaultsKeepOtherModulesFixturesValid() {
            Long productId = fixtures.product("IN_STOCK", "ACTIVE");
            Long optionId = fixtures.option(productId, "ACTIVE");

            Map<String, Object> product = jdbcTemplate.queryForMap(
                    "SELECT visible, base_price, JSON_CONTAINS_PATH(options, 'one', '$.warranty') AS has_warranty FROM products WHERE id = ?", productId);
            assertThat(product.get("visible")).isEqualTo(true);
            assertThat((BigDecimal) product.get("base_price")).isEqualByComparingTo("0");
            assertThat(product.get("has_warranty")).as("옵션 문서에 보증 키가 없다 — 앱은 보증 없음으로 읽는다").isEqualTo(0L);
            // 보증은 옵션 문서로 옮겼다(2026-10-08)
            assertThat(columnExists("products", "warranty_offered")).isFalse();
            assertThat(columnExists("products", "warranty_surcharge")).isFalse();

            assertThat(jdbcTemplate.queryForObject("SELECT price FROM product_options WHERE id = ?", BigDecimal.class, optionId)).isNotNull();
            // 수동 가격 표시는 없앴다(2026-10-06) — 다시 생기면 재계산이 건너뛰는 옵션이 생긴다
            assertThat(columnExists("product_options", "price_overridden")).isFalse();
            // 표시 속성은 없앴다(2026-10-07) — 축 → 값은 조합 키(값 id)와 상품의 옵션 문서가 정본이다
            assertThat(columnExists("product_options", "display_attributes")).isFalse();
        }

        @Test
        @DisplayName("기본가는 음수를 거부한다")
        void negativeAmountsRejected() {
            Long productId = fixtures.product("IN_STOCK", "ACTIVE");
            // MySQL 의 CHECK 위반(3819)은 Spring 이 분류하지 않아 Uncategorized 로 온다. 제약 이름으로 단언한다.
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE products SET base_price = -1 WHERE id = ?", productId))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_product_base_price");
        }
    }

    @Nested
    @DisplayName("옵션 문서 · 조합")
    class Options {

        @Test
        @DisplayName("옵션 칸을 안 넣은 행(다른 모듈 픽스처)은 빈 문서 {} 로, 썸네일 · 멱등 키는 NULL 로 들어간다. NULL 문서는 거절한다")
        void legacyInsertGetsEmptyDocument() {
            Long productId = fixtures.product("IN_STOCK", "ACTIVE");

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT JSON_TYPE(options) AS type, JSON_LENGTH(options) AS length, thumbnail_url, idempotency_key FROM products WHERE id = ?",
                    productId);
            assertThat(row.get("type")).isEqualTo("OBJECT");
            assertThat(row.get("length")).isEqualTo(0L);
            assertThat(row.get("thumbnail_url")).isNull();
            assertThat(row.get("idempotency_key")).isNull();
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE products SET options = NULL WHERE id = ?", productId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("같은 조합의 옵션은 둘일 수 없고, 조합 키가 없는 옵션은 여럿이어도 된다")
        void combinationKeyUniquePerProduct() {
            Long productId = fixtures.product("IN_STOCK", "ACTIVE");
            String key = "12-" + ProductOptions.newValueId();
            fixtures.optionWithCombination(productId, key);
            assertThatThrownBy(() -> fixtures.optionWithCombination(productId, key))
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_option_combination");
            // 다른 상품이면 같은 키라도 된다
            fixtures.optionWithCombination(fixtures.product("IN_STOCK", "ACTIVE"), key);
            // 축이 없는(다른 모듈 픽스처 모양) 옵션은 NULL 키라 몇 개든 들어간다
            fixtures.option(productId, "ACTIVE");
            fixtures.option(productId, "ACTIVE");
        }

        @Test
        @DisplayName("옵션 축 · 값 · 선택 · 사진 · 등록 기록 표는 없다 — 문서 한 칸과 상품 칸으로 옮겼다")
        void optionTablesAreGone() {
            assertThat(jdbcTemplate.queryForList("""
                    SELECT table_name FROM information_schema.tables
                     WHERE table_schema = DATABASE() AND table_name IN
                           ('product_option_axes', 'product_option_values', 'product_option_selections', 'product_images', 'product_registrations')
                    """, String.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("등록 · 아웃박스")
    class Registrations {

        @Test
        @DisplayName("멱등 키는 전체에서 유일하고, 키가 없는 상품은 여럿이어도 된다")
        void idempotencyKeyUnique() {
            Long productId = fixtures.product("PREORDER", "ACTIVE");
            String key = ShopFixtures.unique();
            fixtures.registration(productId, key);

            Long otherProduct = fixtures.product("PREORDER", "ACTIVE");
            assertThatThrownBy(() -> fixtures.registration(otherProduct, key))
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_product_idempotency");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products WHERE id IN (?, ?) AND idempotency_key IS NULL",
                    Long.class, otherProduct, fixtures.product("PREORDER", "ACTIVE"))).isEqualTo(2L);
        }

        @Test
        @DisplayName("catalog 아웃박스의 event_id 는 유일하고 발행 실패 횟수는 음수가 될 수 없다")
        void outboxEventIdUniqueAndAttemptsNonNegative() {
            String eventId = java.util.UUID.randomUUID().toString();
            String insert = """
                    INSERT INTO catalog_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, publish_attempts, created_at)
                    VALUES (?, 'PRODUCT', 1, 'IN_STOCK_PRODUCT_REGISTERED', JSON_OBJECT(), ?, UTC_TIMESTAMP(6))""";
            jdbcTemplate.update(insert, eventId, 0);
            assertThatThrownBy(() -> jdbcTemplate.update(insert, eventId, 0)).isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> jdbcTemplate.update(insert, java.util.UUID.randomUUID().toString(), -1))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_catalog_outbox_attempts");
        }
    }

    private boolean columnExists(String table, String column) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, Long.class, table, column) > 0;
    }
}
