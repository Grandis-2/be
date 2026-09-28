package com.grandis.nova.preorder.accept;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 관리자 대신 접수. 이력에 남길 사유가 필수다. */
public record AdminPreorderRequest(
        @NotNull Long productId,
        @NotNull Long optionId,
        @NotNull Long customerId,
        @NotBlank @Size(max = 500) String reason,
        @Size(max = 1000) String internalNote
) {
}
