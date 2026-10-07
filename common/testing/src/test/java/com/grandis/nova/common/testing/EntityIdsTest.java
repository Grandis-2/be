package com.grandis.nova.common.testing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityIdsTest {

    private static final String FIXTURE = "com.grandis.nova.common.testing.fixture.";

    @Test
    void UUID_id_는_통과한다() {
        assertThatCode(() -> EntityIds.assertUuidIds(FIXTURE + "uuid")).doesNotThrowAnyException();
    }

    @Test
    void UUID_옆의_순번은_통과한다() {
        assertThatCode(() -> EntityIds.assertUuidIds(FIXTURE + "composite")).doesNotThrowAnyException();
    }

    @Test
    void EmbeddedId_의_UUID_칸을_센다() {
        assertThatCode(() -> EntityIds.assertUuidIds(FIXTURE + "embedded")).doesNotThrowAnyException();
    }

    @Test
    void Long_id_뿐이면_막는다() {
        assertThatThrownBy(() -> EntityIds.assertUuidIds(FIXTURE + "longid"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LongIdEntity");
    }

    @Test
    void 엔티티가_없는_패키지는_오타로_본다() {
        assertThatThrownBy(() -> EntityIds.assertUuidIds(FIXTURE + "nothing"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("찾지 못했다");
    }
}
