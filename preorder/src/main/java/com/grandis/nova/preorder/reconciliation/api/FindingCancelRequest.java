package com.grandis.nova.preorder.reconciliation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param reason 예약 이력에 남는 취소 사유(예: "외부에서 취소됨") */
record FindingCancelRequest(@NotBlank @Size(max = 500) String reason) {
}
