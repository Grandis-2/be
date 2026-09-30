package com.grandis.nova.order.order.vo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipToTest {

    @Test
    void blankLine2MeansNoLine2() {
        assertThat(new ShipTo("홍길동", "010-0000-0000", "04524", "세종대로 110", " ").line2()).isNull();
    }

    @Test
    void requiredFieldsMustNotBeBlank() {
        assertThatThrownBy(() -> new ShipTo(" ", "010-0000-0000", "04524", "세종대로 110", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShipTo("홍길동", "010-0000-0000", "04524", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringHidesPersonalData() {
        ShipTo shipTo = new ShipTo("홍길동", "010-1234-5678", "04524", "세종대로 110", "3층");

        assertThat(shipTo.toString()).doesNotContain("홍길동", "010-1234-5678", "04524", "세종대로", "3층");
    }

    // MySQL varchar 는 글자 수로 센다. 한글 50자는 들어가고 51자는 안 된다.
    @Test
    void lengthIsCountedInCharactersLikeDatabase() {
        assertThat(new ShipTo("가".repeat(50), "010", "04524", "세종대로", null).name()).hasSize(50);
        assertThatThrownBy(() -> new ShipTo("가".repeat(51), "010", "04524", "세종대로", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
