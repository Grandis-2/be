package com.grandis.nova.catalog.option;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptionTextTest {

    @Test
    @DisplayName("color · storage 만 목록 필터 축이다 — 키는 접은 뒤(소문자)로 받는다")
    void onlyColorAndStorageAreFilterAxes() {
        assertThat(OptionText.isFilterAxis("color")).isTrue();
        assertThat(OptionText.isFilterAxis("storage")).isTrue();
        assertThat(OptionText.isFilterAxis("length")).isFalse();
        assertThat(OptionText.isFilterAxis("Color")).as("접기는 호출자 몫").isFalse();
    }

    @Test
    @DisplayName("값은 NFC · 트림 · 공백 하나로 접는다 — 콜레이션이 안 해 주는 부분. 용량은 공백을 지우고 대문자로")
    void valuesAreNormalized() {
        assertThat(OptionText.normalize("  Space   Gray ")).isEqualTo("Space Gray");
        assertThat(OptionText.normalizeFor("storage", " 256 gb ")).isEqualTo("256GB");
        assertThat(OptionText.normalizeFor("color", " space  gray ")).isEqualTo("space gray");
        // NFD(자모 분해) 로 들어온 한글도 NFC 한 형태로 — 리터럴은 둘 다 NFC 라 명시적으로 분해해 넣는다
        String decomposed = Normalizer.normalize("블랙", Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("블랙");
        assertThat(OptionText.normalize(decomposed)).isEqualTo("블랙");
        assertThatThrownBy(() -> OptionText.normalize("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OptionText.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
