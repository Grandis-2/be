package com.grandis.nova.catalog;

import com.grandis.nova.catalog.category.Category;
import com.grandis.nova.catalog.category.CategoryRepository;
import com.grandis.nova.catalog.image.ProductImage;
import com.grandis.nova.catalog.image.ProductImageRepository;
import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.ProductOptionAxis;
import com.grandis.nova.catalog.option.ProductOptionAxisRepository;
import com.grandis.nova.catalog.option.ProductOptionSelection;
import com.grandis.nova.catalog.option.ProductOptionSelectionId;
import com.grandis.nova.catalog.option.ProductOptionSelectionRepository;
import com.grandis.nova.catalog.option.ProductOptionValue;
import com.grandis.nova.catalog.option.ProductOptionValueRepository;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.catalog.registration.ProductRegistration;
import com.grandis.nova.catalog.registration.ProductRegistrationRepository;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 엔티티가 마이그레이션 스키마와 맞는지. 컨텍스트가 뜨는 것(ddl-auto: validate)이 절반이고,
 * 나머지 절반은 JDBC 타입 변환이 값을 보존하는지다 — boolean ↔ tinyint(1) · 문자열 ↔ json 은
 * validate 를 지나도 값이 깨질 수 있어 저장한 값을 SQL 로 다시 읽어 대조한다.
 */
@CatalogIntegrationTest
@Transactional
class CatalogEntityMappingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired ProductOptionRepository options;
    @Autowired ProductOptionAxisRepository axes;
    @Autowired ProductOptionValueRepository values;
    @Autowired ProductOptionSelectionRepository selections;
    @Autowired ProductImageRepository images;
    @Autowired ProductRegistrationRepository registrations;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("카테고리를 상위 · 하위로 읽는다")
    void categoryTree() {
        Long parentId = fixtures.category();
        Long samsungId = fixtures.childCategory(parentId, "삼성");
        Long appleId = fixtures.childCategory(parentId, "Apple");

        Category parent = categories.findById(parentId).orElseThrow();
        assertThat(parent.isTopLevel()).isTrue();
        assertThat(parent.getCreatedAt()).isNotNull();
        assertThat(categories.findByCode(parent.getCode())).get().extracting(Category::getId).isEqualTo(parentId);

        List<Category> children = categories.findByParentIdOrderById(parentId);
        assertThat(children).extracting(Category::getId).containsExactly(samsungId, appleId);
        assertThat(children).extracting(Category::getName).containsExactly("삼성", "Apple");
        assertThat(children).allMatch(c -> !c.isTopLevel());
        assertThat(categories.findByParentIdIsNullOrderById()).extracting(Category::getId).contains(parentId)
                .doesNotContain(samsungId, appleId);
    }

    @Test
    @DisplayName("상품 · 옵션은 저장한 값 그대로 DB 에 남는다")
    void productAndOptionRoundTrip() {
        Long categoryId = fixtures.childCategory(fixtures.category(), "Apple");
        Product product = products.saveAndFlush(Product.register(categoryId, SaleMode.PREORDER, "Nova 1",
                new BigDecimal("1200000"), "설명", "nova,phone", true, new BigDecimal("199000")));
        OptionCombination none = OptionCombination.none(product.getId(), " Nova  1 ");
        assertThat(none.isStandalone()).isTrue();
        assertThat(none.title()).isEqualTo("Nova 1");
        assertThat(none.selections(1L)).isEmpty();
        ProductOption option = options.saveAndFlush(ProductOption.of("ONLY", new BigDecimal("1450000"), false, none));
        assertThat(option.getTitle()).isEqualTo("Nova 1");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT sale_mode, status, visible, base_price, warranty_offered, warranty_surcharge, created_at, updated_at "
                        + "FROM products WHERE id = ?", product.getId());
        assertThat(row.get("sale_mode")).isEqualTo("PREORDER");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("visible")).isEqualTo(false);
        assertThat((BigDecimal) row.get("base_price")).isEqualByComparingTo("1200000");
        assertThat(row.get("warranty_offered")).isEqualTo(true);
        assertThat((BigDecimal) row.get("warranty_surcharge")).isEqualByComparingTo("199000");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();

        Map<String, Object> optionRow = jdbcTemplate.queryForMap(
                "SELECT price, price_overridden, status, filter_attributes, display_attributes, combination_key "
                        + "FROM product_options WHERE id = ?", option.getId());
        assertThat((BigDecimal) optionRow.get("price")).isEqualByComparingTo("1450000");
        assertThat(optionRow.get("price_overridden")).isEqualTo(false);
        assertThat(optionRow.get("status")).isEqualTo("ACTIVE");
        assertThat(optionRow.get("filter_attributes")).isNull();
        assertThat(optionRow.get("display_attributes")).isNull();
        assertThat(optionRow.get("combination_key")).isEqualTo("");

        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertThat(reloaded.isVisible()).isFalse();
        assertThat(reloaded.isWarrantyOffered()).isTrue();
        assertThat(reloaded.getStatus()).isEqualTo(SaleStatus.ACTIVE);
        assertThat(options.findByProductIdOrderById(product.getId())).singleElement()
                .satisfies(o -> {
                    assertThat(o.isPriceOverridden()).isFalse();
                    assertThat(o.getCombinationKey()).isEqualTo(OptionCombination.STANDALONE_KEY);
                });
    }

    @Test
    @DisplayName("보증을 제공하지 않으면 추가금은 0 으로 저장한다")
    void warrantySurchargeIgnoredWhenNotOffered() {
        Product product = products.saveAndFlush(Product.register(fixtures.category(), SaleMode.IN_STOCK,
                "Nova Book", BigDecimal.ZERO, null, null, false, new BigDecimal("50000")));
        assertThat(product.getWarrantySurcharge()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("상품은 비공개로 시작하고, 이 상품의 완료된 등록 기록이 있어야 공개된다")
    void productStartsHiddenUntilPublished() {
        Product product = products.saveAndFlush(Product.register(fixtures.category(), SaleMode.IN_STOCK,
                "Nova Book", BigDecimal.ZERO, null, null, false, BigDecimal.ZERO));
        assertThat(visibleInDb(product.getId())).isFalse();

        ProductRegistration incomplete = registrations.saveAndFlush(
                ProductRegistration.start(product.getId(), ShopFixtures.unique(), true,
                        ShopFixtures.inStockPlanWithoutOptions(product.getId())));
        assertThatThrownBy(() -> product.publish(incomplete)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> product.publish(null)).isInstanceOf(IllegalArgumentException.class);

        Product other = products.saveAndFlush(Product.register(fixtures.category(), SaleMode.IN_STOCK,
                "Other", BigDecimal.ZERO, null, null, false, BigDecimal.ZERO));
        ProductRegistration completed = completeRegistration(product.getId(), incomplete.getIdempotencyKey());
        assertThatThrownBy(() -> other.publish(completed)).isInstanceOf(IllegalArgumentException.class);

        product.publish(completed);
        products.saveAndFlush(product);
        assertThat(visibleInDb(product.getId())).isTrue();

        product.hide();
        products.saveAndFlush(product);
        assertThat(visibleInDb(product.getId())).isFalse();
    }

    @Test
    @DisplayName("막힌 등록으로는 공개할 수 없다")
    void blockedRegistrationCannotPublish() {
        Product product = products.saveAndFlush(Product.register(fixtures.category(), SaleMode.PREORDER,
                "Nova 1", BigDecimal.ZERO, null, null, false, BigDecimal.ZERO));
        String key = ShopFixtures.unique();
        registrations.saveAndFlush(ProductRegistration.start(product.getId(), key, true,
                ShopFixtures.preorderPlan(product.getId())));
        jdbcTemplate.update("UPDATE product_registrations SET blocked_reason = 'OPENED_BEFORE_COMPLETE' WHERE product_id = ?",
                product.getId());
        entityManager.clear();
        ProductRegistration blocked = registrations.findByIdempotencyKey(key).orElseThrow();
        assertThat(blocked.isBlocked()).isTrue();
        assertThatThrownBy(() -> products.findById(product.getId()).orElseThrow().publish(blocked))
                .isInstanceOf(IllegalStateException.class);
    }

    private boolean visibleInDb(Long productId) {
        return jdbcTemplate.queryForObject("SELECT visible FROM products WHERE id = ?", Boolean.class, productId);
    }

    /** 완료 시각은 다음 티켓의 서비스가 찍는다. 여기서는 SQL 로 찍고 다시 읽는다. */
    private ProductRegistration completeRegistration(Long productId, String key) {
        jdbcTemplate.update("UPDATE product_registrations SET completed_at = UTC_TIMESTAMP(6) WHERE product_id = ?", productId);
        entityManager.clear();
        return registrations.findByIdempotencyKey(key).orElseThrow();
    }

    @Test
    @DisplayName("옵션 축 · 값 · 선택을 저장하고 상품 단위로 다시 읽는다")
    void optionStructureRoundTrip() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        ProductOptionAxis color = axes.saveAndFlush(ProductOptionAxis.of(productId, ProductOptionAxis.COLOR, "색상", 0));
        ProductOptionAxis length = axes.saveAndFlush(ProductOptionAxis.of(productId, "length", "길이", 1));
        ProductOptionValue black = values.saveAndFlush(ProductOptionValue.of(color.getId(), "블랙", "블랙", BigDecimal.ZERO, 0));
        ProductOptionValue twoMeters = values.saveAndFlush(ProductOptionValue.of(length.getId(), "2m", "2m", new BigDecimal("3000"), 0));
        // 길이 축을 먼저 넘겨도 표시명은 축 position 순, 키는 값 id 순이다
        OptionCombination combination = OptionCombination.of(productId,
                List.of(new OptionCombination.Pick(length, twoMeters), new OptionCombination.Pick(color, black)));
        ProductOption option = options.saveAndFlush(ProductOption.of("BLACK-2M", new BigDecimal("13000"), false, combination));
        selections.saveAllAndFlush(combination.selections(option.getId()));

        assertThat(option.getTitle()).isEqualTo("블랙 / 2m");
        assertThat(option.getCombinationKey()).isEqualTo(Math.min(black.getId(), twoMeters.getId()) + "-"
                + Math.max(black.getId(), twoMeters.getId()));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT combination_key, JSON_EXTRACT(filter_attributes, '$.color') AS color, "
                        + "JSON_EXTRACT(display_attributes, '$.length') AS length, JSON_LENGTH(filter_attributes) AS filters "
                        + "FROM product_options WHERE id = ?", option.getId());
        assertThat(row.get("combination_key")).isEqualTo(option.getCombinationKey());
        assertThat(row.get("color")).isEqualTo("\"블랙\"");
        assertThat(row.get("length")).isEqualTo("\"2m\"");
        assertThat(row.get("filters")).isEqualTo(1L);
        assertThat(combination.covers(axes.findByProductIdOrderByPosition(productId))).isTrue();

        assertThat(color.isFilterAxis()).isTrue();
        assertThat(length.isFilterAxis()).isFalse();
        assertThat(axes.findByProductIdOrderByPosition(productId)).extracting(ProductOptionAxis::getAxisKey)
                .containsExactly("color", "length");
        assertThat(values.findByAxisIdInOrderByAxisIdAscPositionAsc(List.of(color.getId(), length.getId())))
                .extracting(ProductOptionValue::getSurcharge)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(BigDecimal.ZERO, new BigDecimal("3000"));
        List<ProductOptionSelection> selected = selections.findByProductId(productId);
        assertThat(selected).hasSize(2);
        assertThat(selections.findById(new ProductOptionSelectionId(option.getId(), length.getId())))
                .get().extracting(ProductOptionSelection::getValueId).isEqualTo(twoMeters.getId());
    }

    @Test
    @DisplayName("사진은 묶음 · 순서 · 대표를 보존하고 대표 표식은 DB 가 채운다")
    void imageRoundTrip() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        ProductOptionAxis color = axes.saveAndFlush(ProductOptionAxis.of(productId, ProductOptionAxis.COLOR, "색상", 0));
        ProductOptionValue black = values.saveAndFlush(ProductOptionValue.of(color.getId(), "블랙", "블랙", BigDecimal.ZERO, 0));
        ProductImage first = images.saveAndFlush(ProductImage.gallery(productId, color, black, 0, "https://img/1.jpg", true));
        images.saveAndFlush(ProductImage.gallery(productId, color, black, 1, "https://img/2.jpg", false));
        images.saveAndFlush(ProductImage.gallery(productId, null, null, 0, "https://img/0.jpg", true));
        images.saveAndFlush(ProductImage.detail(productId, "  제품  사양 ", 0, "https://img/3.jpg", true));

        assertThat(images.findByProductIdOrderByKindAscBundleKeyAscPositionAsc(productId))
                .extracting(ProductImage::getBundleKey, ProductImage::getUrl)
                .containsExactly(
                        tuple("제품 사양", "https://img/3.jpg"),
                        tuple("", "https://img/0.jpg"),
                        tuple("블랙", "https://img/1.jpg"),
                        tuple("블랙", "https://img/2.jpg"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT primary_marker FROM product_images WHERE id = ?", Integer.class, first.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("GALLERY 묶음은 이 상품 color 축의 값이어야 한다")
    void galleryBundleMustBeColorValueOfProduct() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        ProductOptionAxis color = axes.saveAndFlush(ProductOptionAxis.of(productId, ProductOptionAxis.COLOR, "색상", 0));
        ProductOptionAxis storage = axes.saveAndFlush(ProductOptionAxis.of(productId, ProductOptionAxis.STORAGE, "용량", 1));
        ProductOptionValue black = values.saveAndFlush(ProductOptionValue.of(color.getId(), "블랙", "블랙", BigDecimal.ZERO, 0));
        ProductOptionValue gb256 = values.saveAndFlush(ProductOptionValue.of(storage.getId(), "256GB", "256GB", BigDecimal.ZERO, 0));
        Long otherProduct = fixtures.product("IN_STOCK", "ACTIVE");
        ProductOptionAxis otherColor = axes.saveAndFlush(ProductOptionAxis.of(otherProduct, ProductOptionAxis.COLOR, "색상", 0));

        // 값은 있는데 축이 없음 · 축이 color 가 아님 · 다른 상품의 축 · 값이 그 축의 값이 아님
        assertThatThrownBy(() -> ProductImage.gallery(productId, null, black, 0, "https://img/x.jpg", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductImage.gallery(productId, storage, gb256, 0, "https://img/x.jpg", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductImage.gallery(productId, otherColor, black, 0, "https://img/x.jpg", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductImage.gallery(productId, color, gb256, 0, "https://img/x.jpg", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductImage.detail(productId, "  ", 0, "https://img/x.jpg", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductImage.detail(productId, "spec", 0, " ", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("등록 기록의 공개 요청 · 계획 · 빈 단계 시각이 그대로 남는다 — 계획은 JSON 객체로 저장된다")
    void registrationRoundTrip() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        String key = ShopFixtures.unique();
        String plan = ShopFixtures.inStockPlanWithoutOptions(productId);
        registrations.saveAndFlush(ProductRegistration.start(productId, key, true, plan));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT requested_visible, completed_at, lease_token, JSON_TYPE(plan_payload) AS plan_type, "
                        + "JSON_EXTRACT(plan_payload, '$.productId') AS plan_product FROM product_registrations "
                        + "WHERE product_id = ?", productId);
        assertThat(row.get("requested_visible")).isEqualTo(true);
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("lease_token")).isNull();
        assertThat(row.get("plan_type")).as("문자열이 아니라 JSON 객체로 들어간다").isEqualTo("OBJECT");
        assertThat(row.get("plan_product")).isEqualTo(String.valueOf(productId));

        ProductRegistration reloaded = registrations.findByIdempotencyKey(key).orElseThrow();
        assertThat(reloaded.getProductId()).isEqualTo(productId);
        assertThat(reloaded.getIdempotencyKey()).isEqualTo(key);
        assertThat(reloaded.isRequestedVisible()).isTrue();
        assertThat(JSON.readTree(reloaded.getPlanPayload())).isEqualTo(JSON.readTree(plan));
        assertThat(reloaded.isCompleted()).isFalse();
        assertThat(reloaded.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("계획 칸은 JPA 로 바꿔도 DB 에 반영되지 않는다(updatable = false) — 대조군: 같은 flush 에서 lastError 는 반영된다")
    void planPayloadIsNotUpdatedThroughJpa() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        String key = ShopFixtures.unique();
        String plan = ShopFixtures.inStockPlanWithoutOptions(productId);
        registrations.saveAndFlush(ProductRegistration.start(productId, key, true, plan));
        entityManager.clear();

        ProductRegistration loaded = registrations.findByIdempotencyKey(key).orElseThrow();
        ReflectionTestUtils.setField(loaded, "planPayload", ShopFixtures.preorderPlan(productId));
        ReflectionTestUtils.setField(loaded, "lastError", "probe");
        registrations.flush();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT plan_payload, last_error FROM product_registrations WHERE product_id = ?", productId);
        assertThat(JSON.readTree((String) row.get("plan_payload"))).isEqualTo(JSON.readTree(plan));
        assertThat(row.get("last_error")).isEqualTo("probe");
    }
}
