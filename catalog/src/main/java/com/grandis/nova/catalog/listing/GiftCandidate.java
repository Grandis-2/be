package com.grandis.nova.catalog.listing;

import java.util.List;
import java.util.UUID;

/**
 * 럭키 드로우 증정품으로 고를 수 있는 상품 하나와 그 옵션들 — 관리자 회차 만들기 화면이 한 번에 그린다.
 * 조건은 order 의 회차 만들기 검사(AdminDrawService.requireGiftable)와 같다: 일반 판매 · 상품 판매 중 · 옵션 판매 중 · 재고 행이 있다.
 * 공개 여부는 보지 않는다(매장에 안 파는 증정품은 비공개 상품으로 등록한다).
 *
 * @param visible 공개 여부 — 화면이 "비공개" 표시를 단다
 * @param options 그 상품의 증정품 후보 옵션(하나 이상). 등록 순
 */
public record GiftCandidate(UUID productId, String title, String imageUrl, boolean visible, List<Option> options) {

    /**
     * @param available 지금 확보할 수 있는 수량(총량 − 확보 − 판매). 회차 만들기는 당첨 인원만큼 확보하므로 이보다 크면 409 다.
     *                  만들 때 다시 판정한다 — 이 값은 고르는 동안의 안내다
     */
    public record Option(UUID optionId, String sku, String title, int available) {
    }
}
