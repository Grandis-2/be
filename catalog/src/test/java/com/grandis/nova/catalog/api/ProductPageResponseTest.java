package com.grandis.nova.catalog.api;

import com.grandis.nova.common.OffsetPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductPageResponseTest {

    @Test
    @DisplayName("hasNext 는 다음 쪽을 볼 수 있을 때만 — 전체가 많아도 다음 쪽이 앞 10,000개 밖에서 시작하면 false(그 요청은 400)")
    void hasNextStopsAtTheListDepth() {
        assertThat(ProductPageResponse.from(OffsetPage.of(List.of("x"), 98, 100, 20_000)).hasNext()).isTrue();    // 다음 쪽 9,900 부터
        assertThat(ProductPageResponse.from(OffsetPage.of(List.of("x"), 99, 100, 20_000)).hasNext()).isFalse();   // 다음 쪽 10,000 부터
        assertThat(ProductPageResponse.from(OffsetPage.of(List.of("x"), 832, 12, 20_000)).hasNext()).isTrue();    // 9,996 부터 — 열린다
        assertThat(ProductPageResponse.from(OffsetPage.of(List.of("x"), 833, 12, 20_000)).hasNext()).isFalse();   // 10,008 부터
        assertThat(ProductPageResponse.from(OffsetPage.of(List.of("x"), 0, 20, 20)).hasNext()).as("전체가 한 쪽이면 원래대로").isFalse();
    }
}
