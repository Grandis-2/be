package com.grandis.nova.catalog.option;

import com.grandis.nova.catalog.option.ProductOptions.Axis;
import com.grandis.nova.catalog.option.ProductOptions.Pick;
import com.grandis.nova.catalog.option.ProductOptions.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조합 하나에서 키 · 표시명 · 필터 JSON 이 같은 재료로 나오는지. 값 id 는 옵션 문서 안에 있어 DB 없이 판정한다.
 */
class OptionCombinationTest {

    static final Long PRODUCT_ID = 1L;

    final Value black = value("12", "블랙");
    final Value white = value("9", "화이트");
    final Value gb256 = new Value("0f8c3a1d4b2e4f6a8c9d0e1f2a3b4c5d", "256GB", "256GB", null, new BigDecimal("200000"), List.of());
    final Value twoMeters = value("3", "2m");
    final Axis color = new Axis("color", "색상", List.of(black, white));
    final Axis storage = new Axis("storage", "용량", List.of(gb256));
    final Axis length = new Axis("length", "길이", List.of(twoMeters));
    final ProductOptions document = new ProductOptions(List.of(color, storage, length), List.of(), List.of());

    @Test
    @DisplayName("표시명은 축 순서, 키는 값 id 순서, 필터 JSON 은 color · storage 만")
    void derivedFieldsComeFromOneSource() {
        OptionCombination combination = OptionCombination.of(PRODUCT_ID, List.of(
                new Pick(color, black), new Pick(storage, gb256), new Pick(length, twoMeters)));

        assertThat(combination.title()).isEqualTo("블랙 / 256GB / 2m");
        assertThat(combination.combinationKey()).isEqualTo("3-12-0f8c3a1d4b2e4f6a8c9d0e1f2a3b4c5d");
        assertThat(combination.filterAttributes()).isEqualTo("{\"color\":\"블랙\",\"storage\":\"256GB\"}");
        assertThat(combination.covers(document.axes())).isTrue();

        OptionCombination filterOnly = OptionCombination.of(PRODUCT_ID, List.of(new Pick(color, white)));
        assertThat(filterOnly.filterAttributes()).isEqualTo("{\"color\":\"화이트\"}");
        assertThat(filterOnly.covers(document.axes())).isFalse();
    }

    @Test
    @DisplayName("옮겨 온 숫자 id 는 숫자 순으로 이어 표 시절 키(9-12)와 같다 — 문자열 순이면 12-9 가 된다")
    void numericIdsKeepTheOldKeyOrder() {
        OptionCombination combination = OptionCombination.of(PRODUCT_ID, List.of(
                new Pick(color, black), new Pick(new Axis("size", "크기", List.of(white)), white)));

        assertThat(combination.combinationKey()).isEqualTo("9-12");
    }

    @Test
    @DisplayName("키를 값 id 로 되돌려 문서에서 고르면 축 순서의 같은 조합이 나온다 — 상세 · 재계산이 기대는 왕복")
    void keyRoundTripsThroughTheDocument() {
        OptionCombination combination = OptionCombination.of(PRODUCT_ID, List.of(
                new Pick(color, white), new Pick(storage, gb256), new Pick(length, twoMeters)));

        List<Pick> picks = document.picksOf(OptionCombination.valueIdsOf(combination.combinationKey()));

        assertThat(picks).extracting(pick -> pick.axis().key()).containsExactly("color", "storage", "length");
        assertThat(OptionCombination.of(PRODUCT_ID, picks).combinationKey()).isEqualTo(combination.combinationKey());
        assertThat(OptionCombination.valueIdsOf(OptionCombination.STANDALONE_KEY)).isEmpty();
        assertThat(OptionCombination.valueIdsOf(null)).isEmpty();
    }

    @Test
    @DisplayName("같은 축 두 번 · 다른 축의 값 · 빈 조합은 거절한다")
    void invalidCombinationsRejected() {
        assertThatThrownBy(() -> OptionCombination.of(PRODUCT_ID, List.of(new Pick(color, black), new Pick(color, white))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> OptionCombination.of(PRODUCT_ID, List.of(new Pick(color, gb256))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a value of axis");
        assertThatThrownBy(() -> OptionCombination.of(PRODUCT_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Value value(String id, String text) {
        return new Value(id, text, text, null, BigDecimal.ZERO, List.of());
    }
}
