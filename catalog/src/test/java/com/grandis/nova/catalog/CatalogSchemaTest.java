package com.grandis.nova.catalog;

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
                    INSERT INTO categories (code, name, parent_id, created_at, updated_at)
                    VALUES (?, 'x', 999999999, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, ShopFixtures.unique()))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_category_parent");
        }
    }

    @Nested
    @DisplayName("기존 칼럼만으로 넣은 행")
    class LegacyInserts {

        @Test
        @DisplayName("상품은 공개 · 기본가 0 · 보증 없음으로, 옵션은 수동 가격 아님으로 들어간다")
        void defaultsKeepOtherModulesFixturesValid() {
            Long productId = fixtures.product("IN_STOCK", "ACTIVE");
            Long optionId = fixtures.option(productId, "ACTIVE");

            Map<String, Object> product = jdbcTemplate.queryForMap(
                    "SELECT visible, base_price, warranty_offered, warranty_surcharge FROM products WHERE id = ?", productId);
            assertThat(product.get("visible")).isEqualTo(true);
            assertThat((BigDecimal) product.get("base_price")).isEqualByComparingTo("0");
            assertThat(product.get("warranty_offered")).isEqualTo(false);
            assertThat((BigDecimal) product.get("warranty_surcharge")).isEqualByComparingTo("0");

            Object overridden = jdbcTemplate.queryForObject(
                    "SELECT price_overridden FROM product_options WHERE id = ?", Object.class, optionId);
            assertThat(overridden).isEqualTo(false);
        }

        @Test
        @DisplayName("기본가 · 보증 추가금은 음수를 거부한다")
        void negativeAmountsRejected() {
            Long productId = fixtures.product("IN_STOCK", "ACTIVE");
            // MySQL 의 CHECK 위반(3819)은 Spring 이 분류하지 않아 Uncategorized 로 온다. 제약 이름으로 단언한다.
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE products SET base_price = -1 WHERE id = ?", productId))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_product_base_price");
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE products SET warranty_surcharge = -1 WHERE id = ?", productId))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_product_warranty_surcharge");
        }
    }

    @Nested
    @DisplayName("옵션 축 · 값 · 선택")
    class Options {

        Long productId;
        Long colorAxis;
        Long storageAxis;
        Long black;
        Long gb256;

        @BeforeEach
        void setUp() {
            productId = fixtures.product("IN_STOCK", "ACTIVE");
            colorAxis = fixtures.axis(productId, "color", 0);
            storageAxis = fixtures.axis(productId, "storage", 1);
            black = fixtures.value(colorAxis, "블랙", 0);
            gb256 = fixtures.value(storageAxis, "256GB", 0);
        }

        @Test
        @DisplayName("한 옵션이 같은 축에 값 둘을 가질 수 없다")
        void oneValuePerAxis() {
            Long optionId = fixtures.option(productId, "ACTIVE");
            Long white = fixtures.value(colorAxis, "화이트", 1);
            fixtures.selection(productId, optionId, colorAxis, black);
            assertThatThrownBy(() -> fixtures.selection(productId, optionId, colorAxis, white))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        @DisplayName("다른 축의 값을 이 축에 걸 수 없다")
        void valueMustBelongToAxis() {
            Long optionId = fixtures.option(productId, "ACTIVE");
            assertThatThrownBy(() -> fixtures.selection(productId, optionId, colorAxis, gb256))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_option_selection_value");
        }

        @Test
        @DisplayName("다른 상품의 축이나 옵션을 섞을 수 없다")
        void optionAndAxisMustShareProduct() {
            Long otherProduct = fixtures.product("IN_STOCK", "ACTIVE");
            Long otherOption = fixtures.option(otherProduct, "ACTIVE");
            Long otherAxis = fixtures.axis(otherProduct, "color", 0);
            Long otherBlack = fixtures.value(otherAxis, "블랙", 0);
            Long optionId = fixtures.option(productId, "ACTIVE");

            // 옵션은 이 상품, 축은 다른 상품
            assertThatThrownBy(() -> fixtures.selection(productId, optionId, otherAxis, otherBlack))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_option_selection_axis");
            // 축은 이 상품, 옵션은 다른 상품
            assertThatThrownBy(() -> fixtures.selection(productId, otherOption, colorAxis, black))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_option_selection_option");
            // 둘 다 맞으면 들어간다
            fixtures.selection(productId, optionId, colorAxis, black);
            fixtures.selection(productId, optionId, storageAxis, gb256);
        }

        @Test
        @DisplayName("같은 축의 값은 정규화값 기준 · 대소문자 무시로 유일하다")
        void normalizedValueUniquePerAxisIgnoringCase() {
            assertThatThrownBy(() -> fixtures.value(storageAxis, "256gb", 1))
                    .isInstanceOf(DuplicateKeyException.class);
            // 다른 축이면 같은 값이라도 된다
            Long otherAxis = fixtures.axis(productId, "length", 2);
            fixtures.value(otherAxis, "256GB", 0);
        }

        @Test
        @DisplayName("한 상품에 같은 축 키는 하나다")
        void axisKeyUniquePerProduct() {
            assertThatThrownBy(() -> fixtures.axis(productId, "color", 5))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        @DisplayName("같은 조합의 옵션은 둘일 수 없고, 조합 키가 없는 옵션은 여럿이어도 된다")
        void combinationKeyUniquePerProduct() {
            String key = black + "-" + gb256;
            fixtures.optionWithCombination(productId, key);
            assertThatThrownBy(() -> fixtures.optionWithCombination(productId, key))
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_option_combination");
            // 다른 상품이면 같은 키라도 된다
            fixtures.optionWithCombination(fixtures.product("IN_STOCK", "ACTIVE"), key);
            // 축이 없는(다른 모듈 픽스처 모양) 옵션은 NULL 키라 몇 개든 들어간다
            fixtures.option(productId, "ACTIVE");
            fixtures.option(productId, "ACTIVE");
        }
    }

    @Nested
    @DisplayName("사진")
    class Images {

        Long productId;

        @BeforeEach
        void setUp() {
            productId = fixtures.product("IN_STOCK", "ACTIVE");
        }

        @Test
        @DisplayName("묶음마다 대표는 하나, 대표 아닌 사진은 여러 장")
        void onePrimaryPerBundle() {
            fixtures.image(productId, "GALLERY", "black", 0, true);
            fixtures.image(productId, "GALLERY", "black", 1, false);
            fixtures.image(productId, "GALLERY", "black", 2, false);
            assertThatThrownBy(() -> fixtures.image(productId, "GALLERY", "black", 3, true))
                    .isInstanceOf(DuplicateKeyException.class);
            // 다른 묶음 · 다른 종류는 각자 대표를 갖는다
            fixtures.image(productId, "GALLERY", "white", 0, true);
            fixtures.image(productId, "GALLERY", "", 0, true);
            fixtures.image(productId, "DETAIL", "spec", 0, true);
        }

        @Test
        @DisplayName("묶음 안에서 순서는 겹치지 않는다")
        void positionUniquePerBundle() {
            fixtures.image(productId, "GALLERY", "", 0, false);
            assertThatThrownBy(() -> fixtures.image(productId, "GALLERY", "", 0, false))
                    .isInstanceOf(DuplicateKeyException.class);
            fixtures.image(productId, "DETAIL", "", 0, false);
        }

        @Test
        @DisplayName("대표 표식은 DB 가 계산하고 앱은 쓸 수 없다")
        void primaryMarkerIsGenerated() {
            Long imageId = fixtures.image(productId, "GALLERY", "", 0, true);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT primary_marker FROM product_images WHERE id = ?", Integer.class, imageId)).isEqualTo(1);
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE product_images SET primary_marker = NULL WHERE id = ?", imageId))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("primary_marker");
        }

        @Test
        @DisplayName("종류는 GALLERY · DETAIL 만 받는다")
        void kindIsChecked() {
            assertThatThrownBy(() -> fixtures.image(productId, "BANNER", "", 0, false))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_product_image_kind");
        }
    }

    @Nested
    @DisplayName("등록 기록")
    class Registrations {

        @Test
        @DisplayName("멱등 키는 전체에서 유일하고 상품당 한 행이다")
        void idempotencyKeyAndProductUnique() {
            Long productId = fixtures.product("PREORDER", "ACTIVE");
            String key = ShopFixtures.unique();
            fixtures.registration(productId, key);

            Long otherProduct = fixtures.product("PREORDER", "ACTIVE");
            assertThatThrownBy(() -> fixtures.registration(otherProduct, key))
                    .isInstanceOf(DuplicateKeyException.class);
            assertThatThrownBy(() -> fixtures.registration(productId, ShopFixtures.unique()))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        @DisplayName("리스 토큰과 만료 시각은 같이 있거나 같이 없다")
        void leaseColumnsComeTogether() {
            Long productId = fixtures.product("PREORDER", "ACTIVE");
            fixtures.registration(productId, ShopFixtures.unique());
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE product_registrations SET lease_token = 'x' WHERE product_id = ?", productId))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_registration_lease");
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE product_registrations SET lease_expires_at = UTC_TIMESTAMP(6) WHERE product_id = ?", productId))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_registration_lease");
            assertThat(jdbcTemplate.update(
                    "UPDATE product_registrations SET lease_token = 'x', lease_expires_at = UTC_TIMESTAMP(6) WHERE product_id = ?",
                    productId)).isEqualTo(1);
        }

        @Test
        @DisplayName("막힌 등록은 완료될 수 없고 완료된 등록은 막힐 수 없다")
        void blockedAndCompletedAreExclusive() {
            Long productId = fixtures.product("PREORDER", "ACTIVE");
            fixtures.registration(productId, ShopFixtures.unique());
            assertThat(jdbcTemplate.update(
                    "UPDATE product_registrations SET blocked_reason = 'OPENED_BEFORE_COMPLETE' WHERE product_id = ?", productId))
                    .isEqualTo(1);
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE product_registrations SET completed_at = UTC_TIMESTAMP(6) WHERE product_id = ?", productId))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_registration_outcome");

            Long completed = fixtures.product("PREORDER", "ACTIVE");
            fixtures.registration(completed, ShopFixtures.unique());
            jdbcTemplate.update("UPDATE product_registrations SET completed_at = UTC_TIMESTAMP(6) WHERE product_id = ?", completed);
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE product_registrations SET blocked_reason = 'x' WHERE product_id = ?", completed))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_registration_outcome");
        }
    }
}
