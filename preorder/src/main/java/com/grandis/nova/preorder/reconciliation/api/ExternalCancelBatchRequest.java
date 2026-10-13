package com.grandis.nova.preorder.reconciliation.api;

import com.grandis.nova.preorder.reconciliation.application.ReconciliationAdminService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** @param findingIds 키로 Mock 취소할 불일치 건. be 예약이 취소 완료인 건만(Mock 에만 있음은 한 건씩) */
record ExternalCancelBatchRequest(
        @NotEmpty @Size(max = ReconciliationAdminService.MAX_EXTERNAL_CANCEL_BATCH) List<@NotNull UUID> findingIds) {
}
