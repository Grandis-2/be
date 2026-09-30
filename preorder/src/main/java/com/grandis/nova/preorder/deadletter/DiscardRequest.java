package com.grandis.nova.preorder.deadletter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param note 버리는 까닭. 기록으로 남는다(보낸 쪽 재발행 요청 여부 등) */
record DiscardRequest(@NotBlank @Size(max = 500) String note) {
}
