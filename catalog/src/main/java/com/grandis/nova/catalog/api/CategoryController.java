package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.category.CategoryNode;
import com.grandis.nova.catalog.category.CategoryTreeService;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카테고리 트리 공개 조회. 로그인 없이 볼 수 있다. 상위(모바일 · PC · 액세서리)와 그 아래 하위(삼성 · Apple)를 한 번에 준다.
 * 상품은 상위에 직접 배정될 수도 있으므로 화면은 상위도 선택지로 보인다.
 */
@Tag(name = "카테고리")
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryTreeService treeService;

    public CategoryController(CategoryTreeService treeService) {
        this.treeService = treeService;
    }

    @Operation(summary = "카테고리 트리(로그인 없이)")
    @SecurityRequirements
    @GetMapping
    public ApiResponse<Items<CategoryNode>> tree() {
        return ApiResponse.ok(new Items<>(treeService.tree()));
    }
}
