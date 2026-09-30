package com.grandis.nova.catalog.option;

import com.grandis.nova.catalog.option.OptionCombination.Pick;
import com.grandis.nova.catalog.product.ProductOption;
import com.grandis.nova.catalog.product.ProductOptionRepository;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조합 하나에서 키 · 표시명 · JSON · 선택 행이 같은 재료로 나오는지. id 가 DB 에서 나오므로 통합 시험이다.
 */
@CatalogIntegrationTest
@Transactional
class OptionCombinationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductOptionAxisRepository axes;
    @Autowired ProductOptionValueRepository values;
    @Autowired ProductOptionRepository options;
    @Autowired ProductOptionSelectionRepository selections;

    ShopFixtures fixtures;
    Long productId;
    ProductOptionAxis color;
    ProductOptionAxis storage;
    ProductOptionAxis length;
    ProductOptionValue black;
    ProductOptionValue white;
    ProductOptionValue gb256;
    ProductOptionValue twoMeters;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        productId = fixtures.product("IN_STOCK", "ACTIVE");
        storage = axes.saveAndFlush(ProductOptionAxis.of(productId, "storage", "용량", 1));
        color = axes.saveAndFlush(ProductOptionAxis.of(productId, "Color", "색상", 0));
        length = axes.saveAndFlush(ProductOptionAxis.of(productId, "length", "길이", 2));
        black = values.saveAndFlush(ProductOptionValue.of(color.getId(), "블랙", "블랙", BigDecimal.ZERO, 0));
        white = values.saveAndFlush(ProductOptionValue.of(color.getId(), "화이트", "화이트", BigDecimal.ZERO, 1));
        gb256 = values.saveAndFlush(ProductOptionValue.of(storage.getId(), "256GB", "256GB", new BigDecimal("200000"), 0));
        twoMeters = values.saveAndFlush(ProductOptionValue.of(length.getId(), "2m", "2m", BigDecimal.ZERO, 0));
    }

    @Test
    @DisplayName("표시명은 축 순서, 키는 값 id 순서, 필터 JSON 은 color · storage 만, 나머지는 표시 JSON")
    void derivedFieldsComeFromOneSource() {
        OptionCombination combination = OptionCombination.of(productId, List.of(
                new Pick(length, twoMeters), new Pick(storage, gb256), new Pick(color, black)));

        assertThat(combination.title()).isEqualTo("블랙 / 256GB / 2m");
        assertThat(combination.combinationKey()).isEqualTo(sortedKey(black.getId(), gb256.getId(), twoMeters.getId()));
        assertThat(combination.filterAttributes()).isEqualTo("{\"color\":\"블랙\",\"storage\":\"256GB\"}");
        assertThat(combination.displayAttributes()).isEqualTo("{\"length\":\"2m\"}");
        assertThat(combination.covers(axes.findByProductIdOrderByPosition(productId))).isTrue();

        OptionCombination filterOnly = OptionCombination.of(productId, List.of(new Pick(color, white)));
        assertThat(filterOnly.displayAttributes()).isNull();
        assertThat(filterOnly.covers(axes.findByProductIdOrderByPosition(productId))).isFalse();
    }

    @Test
    @DisplayName("같은 축 두 번 · 다른 축의 값 · 다른 상품의 축 · 빈 조합은 거절한다")
    void invalidCombinationsRejected() {
        assertThatThrownBy(() -> OptionCombination.of(productId, List.of(new Pick(color, black), new Pick(color, white))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> OptionCombination.of(productId, List.of(new Pick(color, gb256))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a value of axis");
        Long otherProduct = fixtures.product("IN_STOCK", "ACTIVE");
        assertThatThrownBy(() -> OptionCombination.of(otherProduct, List.of(new Pick(color, black))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another product");
        assertThatThrownBy(() -> OptionCombination.of(productId, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("저장된 옵션마다 선택 행을 이은 값이 조합 키와 같다 — 셋이 같은 재료에서 나왔다는 불변식")
    void storedKeyMatchesStoredSelections() {
        for (ProductOptionValue chosenColor : List.of(black, white)) {
            OptionCombination combination = OptionCombination.of(productId,
                    List.of(new Pick(color, chosenColor), new Pick(storage, gb256), new Pick(length, twoMeters)));
            ProductOption option = options.saveAndFlush(ProductOption.of(
                    "SKU-" + chosenColor.getId(), new BigDecimal("1200000"), false, combination));
            selections.saveAllAndFlush(combination.selections(option.getId()));
        }

        List<Boolean> consistent = jdbcTemplate.queryForList("""
                SELECT o.combination_key = GROUP_CONCAT(s.value_id ORDER BY s.value_id SEPARATOR '-') AS same
                  FROM product_options o JOIN product_option_selections s ON s.option_id = o.id
                 WHERE o.product_id = ?
                 GROUP BY o.id
                """, Boolean.class, productId);
        assertThat(consistent).hasSize(2).containsOnly(true);
    }

    private static String sortedKey(Long... ids) {
        return java.util.Arrays.stream(ids).sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining("-"));
    }
}
