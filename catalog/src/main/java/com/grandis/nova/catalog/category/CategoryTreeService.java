package com.grandis.nova.catalog.category;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 카테고리 전체를 트리로 읽는다. 행이 열 개 남짓이라 한 번에 읽어 메모리에서 잇는다.
 *
 * 관리자 CRUD 가 없으므로 행(기본 옵션 축 포함)은 운영 init.sql 이 넣는다 — 마이그레이션은 칸만 만든다. FK 는 "상위가 존재한다" 만 지키고 깊이 3 이상 · 순환(자기 참조 포함)은
 * 못 막는다(실측). 그런 행은 여기서 오류 없이 응답에서 빠진다 — 그 카테고리에 배정된 상품도 트리로는 닿지 않는다.
 * 그래서 막을 자리는 읽는 쪽이 아니라 행을 넣는 쪽이다(넣은 뒤 깊이 ≤ 2 · 순환 없음을 SQL 로 검증). 빠지는 행은 경고 로그로 남긴다.
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
        List<Category> all = categories.findAllByOrderBySortOrderAscCreatedAtAscIdAsc();
        Set<UUID> rootIds = new HashSet<>();
        for (Category category : all) {
            if (category.isTopLevel()) {
                rootIds.add(category.getId());
            }
        }
        Map<UUID, List<CategoryNode>> childrenByParent = new LinkedHashMap<>();
        List<UUID> dropped = new ArrayList<>();
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
        return new CategoryNode(category.getId(), category.getName(), category.getParentId(), optionAxesOf(category), children);
    }

    /**
     * 기본 옵션 축. DB CHECK 는 배열인지만 본다 — 원소가 문자열이거나 key · label 이 없는 행(init.sql 실수)은 그 행만 빈 배열로 두고 경고한다.
     * 화면을 미리 채우는 틀일 뿐이라 카테고리는 트리에 남긴다. 예외를 그대로 두면 공개 트리 전체가 500 이었다(실측).
     */
    private static List<CategoryNode.OptionAxis> optionAxesOf(Category category) {
        try {
            List<CategoryNode.OptionAxis> axes = category.getOptionAxes();
            if (axes.stream().allMatch(axis -> axis != null && hasText(axis.key()) && hasText(axis.label()))) {
                return axes;
            }
        } catch (JacksonException e) {
            // 아래에서 경고하고 빈 배열
        }
        log.warn("기본 옵션 축의 모양이 틀린 카테고리 — 빈 배열로 싣는다. id={}", category.getId());
        return List.of();
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
