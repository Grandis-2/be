package com.grandis.nova.preorder.accept.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 관리자 대신 접수. 이력에 남길 사유가 필수다. */
record AdminPreorderRequest(
        @NotNull UUID productId,
        @NotNull UUID optionId,
        @NotNull UUID customerId,
        @NotBlank @Size(max = 500) String reason,
        @Size(max = 1000) String internalNote
) {
}
