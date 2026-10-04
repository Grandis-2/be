package com.grandis.nova.preorder.cancel.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 취소. 사유는 필수이며 actor = ADMIN 으로 이력에 남는다. */
record AdminCancelRequest(
        @NotBlank @Size(min = 5, max = 500) String reason
) {
}
