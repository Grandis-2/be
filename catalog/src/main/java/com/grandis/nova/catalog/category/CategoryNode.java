package com.grandis.nova.catalog.category;

import java.util.List;
import java.util.UUID;

/**
 * 카테고리 트리의 한 노드. 상위는 parentId 가 null 이고 children 에 하위(삼성 · Apple)가 온다.
 * 배열 순서가 곧 표시 순서다 — categories.sort_order 오름차순, 같으면 생성 순(created_at, 그다음 id). 순서 칸 자체는 싣지 않는다(명세).
 * 노출(그 카테고리에 판매 중 상품이 있는가)은 화면이 판정한다.
 */
public record CategoryNode(UUID categoryId, String name, UUID parentId, List<CategoryNode> children) {

    public CategoryNode {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
