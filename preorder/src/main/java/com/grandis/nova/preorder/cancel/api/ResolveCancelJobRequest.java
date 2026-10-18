package com.grandis.nova.preorder.cancel.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 포기한 외부 취소의 수동 완료. 사유는 필수이며 actor = ADMIN 으로 예약 이력에 남는다(외부에서 무엇을 확인했는지). */
record ResolveCancelJobRequest(
        @NotBlank @Size(min = 5, max = 500) String reason
) {
}
