package com.grandis.nova.catalog.category;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * 카테고리 트리의 한 노드. 상위는 parentId 가 null 이고 children 에 하위(스마트폰 · 태블릿)가 온다.
 * 배열 순서가 곧 표시 순서다 — categories.sort_order 오름차순, 같으면 생성 순(created_at, 그다음 id). 순서 칸 자체는 싣지 않는다(명세).
 * 노출(그 카테고리에 판매 중 상품이 있는가)은 화면이 판정한다.
 *
 * @param optionAxes 관리자 등록 화면이 상품 등록 요청의 optionAxes 를 처음에 채우는 틀. 상위 · 축 없는 카테고리는 빈 배열.
 *                   등록 검증은 이 값을 보지 않는다 — 같은 카테고리라도 축이 다른 상품이 있다
 */
public record CategoryNode(UUID categoryId, String name, UUID parentId, List<OptionAxis> optionAxes, List<CategoryNode> children) {

    public CategoryNode {
        optionAxes = optionAxes == null ? List.of() : List.copyOf(optionAxes);
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** @param key 상품 등록의 축 키와 같은 모양(소문자) */
    @Schema(name = "CategoryOptionAxis")
    public record OptionAxis(String key, String label) {
    }
}
