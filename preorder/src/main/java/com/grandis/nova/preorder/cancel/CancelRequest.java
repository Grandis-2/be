package com.grandis.nova.preorder.cancel;

import jakarta.validation.constraints.Size;

/** 사용자 취소. 사유는 선택이며 이력에 남는다. */
record CancelRequest(
        @Size(max = 500) String reason
) {
}
