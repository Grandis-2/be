package com.grandis.nova.preorder.accept;

import jakarta.validation.constraints.NotNull;

/** 접수 요청. 한 모델의 한 옵션, 수량 1(수량 칸 없음). */
public record PreorderRequest(
        @NotNull Long productId,
        @NotNull Long optionId
) {
}
