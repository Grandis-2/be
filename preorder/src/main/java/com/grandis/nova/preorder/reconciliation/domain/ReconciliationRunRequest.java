package com.grandis.nova.preorder.reconciliation.domain;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 관리자 실행 요청 — reconciliation_runs 에 REQUESTED 한 줄을 넣는다(preorder 의 허용된 쓰기). batch 가 몇 초 안에 가져가
 * 실행 중 · 완료 · 실패로 바꾸므로 넣기만 하고 고치지 않는다. 대기 · 실행 중 표식(active_marker)은 DB 가 계산하는 칸이라 매핑하지 않는다.
 */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRunRequest extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 64)
    private String requestedBy;

    @Column(updatable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private RunStatus status;

    protected ReconciliationRunRequest() {
    }

    /** @param productId 검사 범위. null 이면 전체 */
    public static ReconciliationRunRequest of(String requestedBy, UUID productId) {
        ReconciliationRunRequest request = new ReconciliationRunRequest();
        request.requestedBy = requestedBy;
        request.productId = productId;
        request.status = RunStatus.REQUESTED;
        return request;
    }
}
