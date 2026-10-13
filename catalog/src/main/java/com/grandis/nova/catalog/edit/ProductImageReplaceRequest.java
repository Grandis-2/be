package com.grandis.nova.catalog.edit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /admin/products/{id}/images — 사진 한 장을 다른 사진으로 바꾼다. 자리 · 대표 여부는 그대로다.
 *
 * @param url    바꿀 사진(이 상품에 있는 주소)
 * @param newUrl 업로드 API 가 준 새 imageUrl. 이 상품에 이미 있는 주소는 400
 */
public record ProductImageReplaceRequest(@NotBlank @Size(max = 1000) String url, @NotBlank @Size(max = 1000) String newUrl) {
}
