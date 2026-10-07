package com.grandis.nova.catalog.category;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 카테고리 전체를 트리로 읽는다. 행이 열 개 남짓이라 한 번에 읽어 메모리에서 잇는다.
 *
 * 관리자 CRUD 가 없으므로 행은 마이그레이션 시드가 넣는다. FK 는 "상위가 존재한다" 만 지키고 깊이 3 이상 · 순환(자기 참조 포함)은
 * 못 막는다(실측). 그런 행은 여기서 오류 없이 응답에서 빠진다 — 그 카테고리에 배정된 상품도 트리로는 닿지 않는다.
 * 그래서 막을 자리는 읽는 쪽이 아니라 시드다(시드 뒤 깊이 ≤ 2 · 순환 없음을 SQL 로 검증). 빠지는 행은 경고 로그로 남긴다.
 */
@Service
public class CategoryTreeService {

    private static final Logger log = LoggerFactory.getLogger(CategoryTreeService.class);

    private final CategoryRepository categories;

    public CategoryTreeService(CategoryRepository categories) {
        this.categories = categories;
    }

    /** 상위 목록(표시 순서). 각 상위의 children 에 하위가 같은 순서로 온다. 상위 아래 하위까지 두 단계만 싣는다. */
    @Transactional(readOnly = true)
    public List<CategoryNode> tree() {
        List<Category> all = categories.findAllByOrderBySortOrderAscIdAsc();
        Set<Long> rootIds = new HashSet<>();
        for (Category category : all) {
            if (category.isTopLevel()) {
                rootIds.add(category.getId());
            }
        }
        Map<Long, List<CategoryNode>> childrenByParent = new LinkedHashMap<>();
        List<Long> dropped = new ArrayList<>();
        for (Category category : all) {
            if (category.isTopLevel()) {
                continue;
            }
            if (rootIds.contains(category.getParentId())) {
                childrenByParent.computeIfAbsent(category.getParentId(), id -> new ArrayList<>())
                        .add(toNode(category, List.of()));
            } else {
                dropped.add(category.getId());
            }
        }
        if (!dropped.isEmpty()) {
            log.warn("상위가 최상위가 아닌 카테고리를 응답에서 뺐다(깊이 3 이상 또는 순환). ids={}", dropped);
        }
        List<CategoryNode> roots = new ArrayList<>();
        for (Category category : all) {
            if (category.isTopLevel()) {
                roots.add(toNode(category, childrenByParent.getOrDefault(category.getId(), List.of())));
            }
        }
        return roots;
    }

    private static CategoryNode toNode(Category category, List<CategoryNode> children) {
        return new CategoryNode(category.getId(), category.getName(), category.getParentId(), children);
    }
}
