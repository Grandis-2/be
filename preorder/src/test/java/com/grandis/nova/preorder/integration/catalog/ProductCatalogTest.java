package com.grandis.nova.preorder.integration.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 회차 설정은 사전예약 상품이면 되고, 접수는 판매 중 · 공개 · 등록 완료까지 본다. */
class ProductCatalogTest {

    @Test
    void 접수는_판매_중이고_공개됐고_등록이_끝난_사전예약_상품만_받는다() {
        assertThat(product("PREORDER", "ACTIVE", true, true).isOnPreorderSale()).isTrue();
        assertThat(product("PREORDER", "ACTIVE", false, true).isOnPreorderSale()).isFalse();
        assertThat(product("PREORDER", "ACTIVE", true, false).isOnPreorderSale()).isFalse();
        assertThat(product("PREORDER", "PAUSED", true, true).isOnPreorderSale()).isFalse();
        assertThat(product("IN_STOCK", "ACTIVE", true, true).isOnPreorderSale()).isFalse();
    }

    @Test
    void 공개_등록_칸이_없는_응답은_숨기지_않는다() {
        assertThat(product("PREORDER", "ACTIVE", null, null).isOnPreorderSale()).isTrue();
    }

    @Test
    void 회차_설정은_판매_공개와_무관하게_사전예약_상품이면_된다() {
        assertThat(product("PREORDER", "PAUSED", false, false).isPreorderProduct()).isTrue();
        assertThat(product("IN_STOCK", "ACTIVE", true, true).isPreorderProduct()).isFalse();
    }

    private ProductCatalog product(String saleMode, String status, Boolean visible, Boolean registrationCompleted) {
        return new ProductCatalog(7L, "Nova 1", saleMode, status, visible, registrationCompleted, List.of());
    }
}
