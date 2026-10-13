package com.grandis.nova.catalog.edit;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /admin/products/{id}/images — 사진 한 장을 그 묶음 맨 뒤에 넣는다.
 *
 * @param kind   GALLERY(상품 사진) · DETAIL(상세 영역)
 * @param bundle GALLERY 면 color 축의 값(color 축이 없는 상품은 비운다), DETAIL 이면 영역 이름(없는 영역이면 맨 뒤에 새로 만든다)
 * @param url    업로드 API 가 준 imageUrl. 이 상품에 이미 있는 주소는 400
 */
public record ProductImageAddRequest(@NotNull Kind kind, @Size(max = 60) String bundle, @NotBlank @Size(max = 1000) String url) {

    @Schema(name = "ProductImageKind")
    public enum Kind {
        GALLERY,
        DETAIL
    }
}
