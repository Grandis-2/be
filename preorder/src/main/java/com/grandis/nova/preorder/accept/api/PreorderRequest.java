package com.grandis.nova.preorder.accept.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** 접수 요청. 한 모델의 한 옵션, 수량 1(수량 칸 없음). */
record PreorderRequest(
        @NotNull UUID productId,
        @NotNull UUID optionId
) {
}
