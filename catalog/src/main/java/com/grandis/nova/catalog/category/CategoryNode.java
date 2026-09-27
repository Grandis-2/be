package com.grandis.nova.catalog.category;

import java.util.List;

/**
 * 카테고리 트리의 한 노드. 상위는 parentId 가 null 이고 children 에 하위(삼성 · Apple)가 id 순으로 온다.
 * 순서는 id(생성 순)다 — 별도 정렬 칸을 두지 않는다. 그래서 표시 순서(모바일 / PC / 액세서리, 삼성 / Apple)는 시드가 그 순서로
 * INSERT 해야만 나온다(INSERT…SELECT 면 ORDER BY 필수). 상품이 카테고리 id 를 참조하기 시작하면 지우고 다시 넣어 순서를 바꿀 수
 * 없으므로, 순서를 바꿀 일이 생기면 그때 정렬 칸을 추가한다. 노출(그 카테고리에 판매 중 상품이 있는가)은 화면이 판정한다.
 */
public record CategoryNode(Long categoryId, String code, String name, Long parentId, List<CategoryNode> children) {

    public CategoryNode {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
