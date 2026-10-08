package com.grandis.nova.catalog;

import com.grandis.nova.catalog.category.Category;
import com.grandis.nova.catalog.category.CategoryRepository;
import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.catalog.option.OptionText;
import com.grandis.nova.catalog.option.ProductOptions;
import com.grandis.nova.catalog.option.ProductOptions.Axis;
import com.grandis.nova.catalog.option.ProductOptions.Image;
import com.grandis.nova.catalog.option.ProductOptions.Pick;
import com.grandis.nova.catalog.option.ProductOptions.Section;
import com.grandis.nova.catalog.option.ProductOptions.Value;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.product.SaleStatus;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 엔티티가 마이그레이션 스키마와 맞는지. 컨텍스트가 뜨는 것(ddl-auto: validate)이 절반이고,
 * 나머지 절반은 JDBC 타입 변환이 값을 보존하는지다 — boolean ↔ tinyint(1) · byte[] ↔ binary(32) · 문자열 ↔ json 은
 * validate 를 지나도 값이 깨질 수 있어 저장한 값을 SQL 로 다시 읽어 대조한다.
 */
@CatalogIntegrationTest
@Transactional
class CatalogEntityMappingTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired ProductOptionRepository options;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("카테고리를 상위 · 하위로 읽는다")
    void categoryTree() {
        UUID parentId = fixtures.category();
        UUID samsungId = fixtures.childCategory(parentId, "삼성");
        UUID appleId = fixtures.childCategory(parentId, "Apple");

        Category parent = categories.findById(parentId).orElseThrow();
        assertThat(parent.isTopLevel()).isTrue();
        assertThat(parent.getCreatedAt()).isNotNull();
        assertThat(parent.getSortOrder()).as("순서 칸을 안 넣은 행(다른 모듈 픽스처)은 0").isZero();

        List<Category> children = categories.findAllByOrderBySortOrderAscCreatedAtAscIdAsc().stream()
                .filter(c -> parentId.equals(c.getParentId())).toList();
        assertThat(children).extracting(Category::getId).containsExactly(samsungId, appleId);
        assertThat(children).extracting(Category::getName).containsExactly("삼성", "Apple");
        assertThat(children).allMatch(c -> !c.isTopLevel());
    }

    @Test
    @DisplayName("상품 · 옵션은 저장한 값 그대로 DB 에 남는다")
    void productAndOptionRoundTrip() {
        UUID categoryId = fixtures.childCategory(fixtures.category(), "Apple");
        Product product = products.saveAndFlush(Product.register(null, categoryId, SaleMode.PREORDER, "Nova 1",
                new BigDecimal("1200000"), "설명", "nova,phone", false, true, new BigDecimal("199000"), ProductOptions.EMPTY));
        OptionCombination none = OptionCombination.none(product.getId(), " Nova  1 ");
        assertThat(none.isStandalone()).isTrue();
        assertThat(none.title()).isEqualTo("Nova 1");
        assertThat(none.getPicks()).isEmpty();
        ProductOption option = options.saveAndFlush(ProductOption.of("ONLY", new BigDecimal("1450000"), none));
        assertThat(option.getTitle()).isEqualTo("Nova 1");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT sale_mode, status, visible, base_price, options->'$.warranty.offered' AS warranty_offered, "
                        + "JSON_TYPE(options->'$.warranty.surcharge') AS surcharge_type, options->>'$.warranty.surcharge' AS warranty_surcharge, created_at, updated_at "
                        + "FROM products WHERE id = ?", UuidBinary.toBytes(product.getId()));
        assertThat(row.get("sale_mode")).isEqualTo("PREORDER");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("visible")).isEqualTo(false);
        assertThat((BigDecimal) row.get("base_price")).isEqualByComparingTo("1200000");
        assertThat(row.get("warranty_offered")).as("보증은 옵션 문서의 warranty").isEqualTo("true");
        assertThat(row.get("surcharge_type")).as("정수 원 — 실수(1.99E+5)로 들어가지 않는다").isEqualTo("INTEGER");
        assertThat(new BigDecimal((String) row.get("warranty_surcharge"))).isEqualByComparingTo("199000");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();

        Map<String, Object> optionRow = jdbcTemplate.queryForMap(
                "SELECT price, status, filter_attributes, combination_key "
                        + "FROM product_options WHERE id = ?", UuidBinary.toBytes(option.getId()));
        assertThat((BigDecimal) optionRow.get("price")).isEqualByComparingTo("1450000");
        assertThat(optionRow.get("status")).isEqualTo("ACTIVE");
        assertThat(optionRow.get("filter_attributes")).isNull();
        assertThat(optionRow.get("combination_key")).isEqualTo("");

        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertThat(reloaded.isVisible()).isFalse();
        assertThat(reloaded.isWarrantyOffered()).isTrue();
        assertThat(reloaded.getWarrantySurcharge()).isEqualByComparingTo("199000");
        assertThat(reloaded.getStatus()).isEqualTo(SaleStatus.ACTIVE);
        assertThat(options.findByProductIdOrderByCreatedAtAscIdAsc(product.getId())).singleElement()
                .satisfies(o -> assertThat(o.getCombinationKey()).isEqualTo(OptionCombination.STANDALONE_KEY));
    }

    @Test
    @DisplayName("보증을 제공하지 않으면 추가금은 0 으로 저장하고, 보증을 바꿔도 옵션 문서의 다른 내용 · 썸네일은 그대로다")
    void warrantySurchargeIgnoredWhenNotOffered() {
        ProductOptions document = new ProductOptions(List.of(new Axis(OptionText.COLOR, "색상", List.of(
                new Value("1", "블랙", "블랙", null, BigDecimal.ZERO, List.of(new Image("https://img/b.jpg", false)))))), List.of(), List.of(), ProductOptions.Warranty.NONE);
        Product product = products.saveAndFlush(Product.register(null, fixtures.category(), SaleMode.IN_STOCK,
                "Nova Book", BigDecimal.ZERO, null, null, false, false, new BigDecimal("50000"), document));
        assertThat(product.getWarrantySurcharge()).isEqualByComparingTo("0");
        assertThat(product.isWarrantyOffered()).isFalse();

        product.setWarranty(true, new BigDecimal("1.5e3"));
        products.saveAndFlush(product);
        entityManager.clear();
        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertThat(reloaded.isWarrantyOffered()).isTrue();
        assertThat(reloaded.getWarrantySurcharge().toPlainString()).isEqualTo("1500");
        assertThat(reloaded.getOptions().axes()).isEqualTo(document.axes());
        assertThat(reloaded.getThumbnailUrl()).isEqualTo("https://img/b.jpg");

        reloaded.setWarranty(false, new BigDecimal("99"));
        assertThat(reloaded.getWarrantySurcharge()).as("제공하지 않으면 추가금은 보지 않는다").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("상품은 등록 때 고른 공개 여부로 시작하고, 공개 · 비공개 전환은 조건 없이 된다 — 노출은 판매 방식별 준비를 함께 본다")
    void productStartsWithChosenVisibility() {
        Product hidden = products.saveAndFlush(Product.register(null, fixtures.category(), SaleMode.IN_STOCK,
                "Nova Book", BigDecimal.ZERO, null, null, false, false, BigDecimal.ZERO, ProductOptions.EMPTY));
        assertThat(visibleInDb(hidden.getId())).isFalse();
        Product shown = products.saveAndFlush(Product.register(null, fixtures.category(), SaleMode.IN_STOCK,
                "Nova Book", BigDecimal.ZERO, null, null, true, false, BigDecimal.ZERO, ProductOptions.EMPTY));
        assertThat(visibleInDb(shown.getId())).isTrue();

        hidden.publish();
        products.saveAndFlush(hidden);
        assertThat(visibleInDb(hidden.getId())).isTrue();

        hidden.hide();
        products.saveAndFlush(hidden);
        assertThat(visibleInDb(hidden.getId())).isFalse();
    }

    private boolean visibleInDb(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT visible FROM products WHERE id = ?", Boolean.class, UuidBinary.toBytes(productId));
    }

    @Test
    @DisplayName("옵션 문서는 축 · 값 · 추가금 · hex · 사진 순서를 그대로 JSON 에 남기고 다시 읽는다")
    void optionDocumentRoundTrip() {
        Value black = new Value(ProductOptions.newValueId(), "블랙", "블랙", "#2E2E32", BigDecimal.ZERO,
                List.of(new Image("https://img/b2.jpg", false), new Image("https://img/b1.jpg", true)));
        Value twoMeters = new Value(ProductOptions.newValueId(), "2m", "2m", null, new BigDecimal("3000"), List.of());
        Axis color = new Axis(OptionText.COLOR, "색상", List.of(black));
        Axis length = new Axis("length", "길이", List.of(twoMeters));
        ProductOptions document = new ProductOptions(List.of(color, length), List.of(),
                List.of(new Section("제품 사양", List.of(new Image("https://img/spec.jpg", false)))), ProductOptions.Warranty.NONE);
        Product product = products.saveAndFlush(Product.register(null, fixtures.category(), SaleMode.IN_STOCK, "Nova Cable",
                new BigDecimal("10000"), null, null, false, false, BigDecimal.ZERO, document));
        entityManager.clear();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT options->>'$.axes[0].key' AS first_axis, options->>'$.axes[0].values[0].hex' AS hex,
                       options->>'$.axes[0].values[0].images[1].url' AS second_image,
                       JSON_EXTRACT(options, '$.axes[1].values[0].surcharge') AS surcharge,
                       options->>'$.detailImages[0].section' AS section, thumbnail_url
                  FROM products WHERE id = ?""", UuidBinary.toBytes(product.getId()));
        assertThat(row.get("first_axis")).isEqualTo("color");
        assertThat(row.get("hex")).isEqualTo("#2E2E32");
        assertThat(row.get("second_image")).isEqualTo("https://img/b1.jpg");
        assertThat(new BigDecimal(row.get("surcharge").toString())).isEqualByComparingTo("3000");
        assertThat(row.get("section")).isEqualTo("제품 사양");
        assertThat(row.get("thumbnail_url")).as("첫 색상의 첫 장 — 대표 표시가 둘째 장에 있어도 첫 장").isEqualTo("https://img/b2.jpg");
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_KEYS(options, '$.axes[0]') FROM products WHERE id = ?", String.class, UuidBinary.toBytes(product.getId())))
                .as("파생 값(filterAxis)은 문서에 쓰지 않는다 — 문서 모양은 백필과 같다").isEqualTo("[\"key\", \"label\", \"values\"]");

        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getOptions()).isEqualTo(document);
        assertThat(reloaded.getThumbnailUrl()).isEqualTo("https://img/b2.jpg");
    }

    @Test
    @DisplayName("옵션 조합은 문서의 값 id 로 키를 만들고, 필터 JSON 은 color · storage 만 담는다")
    void optionCombinationFromDocument() {
        Value black = new Value(ProductOptions.newValueId(), "블랙", "블랙", null, BigDecimal.ZERO, List.of());
        Value twoMeters = new Value(ProductOptions.newValueId(), "2m", "2m", null, new BigDecimal("3000"), List.of());
        Axis color = new Axis(OptionText.COLOR, "색상", List.of(black));
        Axis length = new Axis("length", "길이", List.of(twoMeters));
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        OptionCombination combination = OptionCombination.of(productId, List.of(new Pick(color, black), new Pick(length, twoMeters)));
        ProductOption option = options.saveAndFlush(ProductOption.of("BLACK-2M", new BigDecimal("13000"), combination));

        assertThat(option.getTitle()).isEqualTo("블랙 / 2m");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT combination_key, JSON_EXTRACT(filter_attributes, '$.color') AS color, "
                        + "JSON_LENGTH(filter_attributes) AS filters "
                        + "FROM product_options WHERE id = ?", UuidBinary.toBytes(option.getId()));
        assertThat(OptionCombination.valueIdsOf((String) row.get("combination_key"))).containsExactlyInAnyOrder(black.id(), twoMeters.id());
        assertThat(row.get("color")).isEqualTo("\"블랙\"");
        assertThat(row.get("filters")).isEqualTo(1L);
        assertThat(color.isFilterAxis()).isTrue();
        assertThat(length.isFilterAxis()).isFalse();
    }

    @Test
    @DisplayName("썸네일은 첫 색상의 첫 장(대표 표시는 안 본다), 첫 색상에 사진이 없으면 null(다음 색상으로 안 넘어간다), 색상 축이 없으면 기본 묶음의 첫 장")
    void thumbnailRule() {
        Value black = new Value("1", "블랙", "블랙", null, BigDecimal.ZERO, List.of());
        Value white = new Value("2", "화이트", "화이트", null, BigDecimal.ZERO,
                List.of(new Image("https://img/w1.jpg", false), new Image("https://img/w2.jpg", false)));
        Value blue = new Value("3", "블루", "블루", null, BigDecimal.ZERO, List.of(new Image("https://img/u1.jpg", true)));
        List<Image> defaults = List.of(new Image("https://img/d1.jpg", false), new Image("https://img/d2.jpg", true));

        assertThat(new ProductOptions(List.of(new Axis(OptionText.COLOR, "색상", List.of(black, white, blue))), List.of(), List.of(), ProductOptions.Warranty.NONE)
                .thumbnailUrl()).as("첫 색상 블랙에 사진이 없다 — 화이트로 넘어가지 않는다").isNull();
        assertThat(new ProductOptions(List.of(new Axis(OptionText.COLOR, "색상", List.of(blue, white))), List.of(), List.of(), ProductOptions.Warranty.NONE)
                .thumbnailUrl()).isEqualTo("https://img/u1.jpg");
        assertThat(new ProductOptions(List.of(new Axis(OptionText.COLOR, "색상", List.of(white, blue))), defaults, List.of(), ProductOptions.Warranty.NONE)
                .thumbnailUrl()).as("색상 축이 있으면 기본 묶음은 안 본다").isEqualTo("https://img/w1.jpg");
        assertThat(new ProductOptions(List.of(new Axis("length", "길이", List.of(black))), defaults, List.of(), ProductOptions.Warranty.NONE).thumbnailUrl())
                .as("대표(d2)가 아니라 첫 장").isEqualTo("https://img/d1.jpg");
        assertThat(ProductOptions.EMPTY.thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("멱등 키는 상품 칸에 남아 키로 다시 찾는다 — 키 없는 행(다른 모듈 픽스처)은 여럿이어도 된다")
    void idempotencyKeyRoundTrip() {
        String key = ShopFixtures.unique();
        UUID categoryId = fixtures.category();
        Product product = products.saveAndFlush(Product.register(key, categoryId, SaleMode.PREORDER, "Nova 1",
                BigDecimal.ONE, null, null, false, false, BigDecimal.ZERO, ProductOptions.EMPTY));
        products.saveAndFlush(Product.register(null, categoryId, SaleMode.IN_STOCK, "A", BigDecimal.ONE, null, null, false, false,
                BigDecimal.ZERO, ProductOptions.EMPTY));
        products.saveAndFlush(Product.register(null, categoryId, SaleMode.IN_STOCK, "B", BigDecimal.ONE, null, null, false, false,
                BigDecimal.ZERO, ProductOptions.EMPTY));

        assertThat(products.findByIdempotencyKey(key)).get().extracting(Product::getId).isEqualTo(product.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products WHERE category_id = ? AND idempotency_key IS NULL",
                Long.class, UuidBinary.toBytes(categoryId))).isEqualTo(2L);
    }
}
