package com.grandis.nova.batch.reconciliation.run;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 찾은 불일치 한 건(reconciliation_findings). batch 는 넣기만 하고, 조치 칸(action_*)은 preorder 가 관리자 버튼을 받아 바꾼다.
 * 그래서 이 엔티티는 조치 칸 가운데 넣을 때 정하는 상태(NOT_STARTED)만 매핑한다.
 */
@Entity
@Table(name = "reconciliation_findings")
public class ReconciliationFinding extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private UUID runId;

    /** 확인할 것 코드(결과-테이블-설계 3장). 종류가 늘어도 마이그레이션이 붙지 않게 문자열이다 */
    @Column(nullable = false, updatable = false, length = 40)
    private String checkCode;

    /** 불일치 대상 — 예약 · Mock 건은 외부 키, 순번 빈틈은 상품 id, 재고는 옵션 id */
    @Column(nullable = false, updatable = false, length = 100)
    private String targetKey;

    /** 불일치 대상의 상품. 알 수 없으면 비어 있다 */
    @Column(updatable = false)
    private UUID productId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String beSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private String mockSnapshot;

    @Column(nullable = false, updatable = false)
    private Instant firstDetectedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ActionStatus actionStatus;

    protected ReconciliationFinding() {
    }

    public ReconciliationFinding(UUID runId, String checkCode, String targetKey, UUID productId, String beSnapshot,
                                 String mockSnapshot, Instant firstDetectedAt) {
        if (beSnapshot == null && mockSnapshot == null) {
            throw new IllegalArgumentException("be · Mock 값이 둘 다 없는 결과는 남기지 않는다: " + checkCode + " " + targetKey);
        }
        this.runId = runId;
        this.checkCode = checkCode;
        this.targetKey = targetKey;
        this.productId = productId;
        this.beSnapshot = beSnapshot;
        this.mockSnapshot = mockSnapshot;
        this.firstDetectedAt = firstDetectedAt;
        this.actionStatus = ActionStatus.NOT_STARTED;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getCheckCode() {
        return checkCode;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getBeSnapshot() {
        return beSnapshot;
    }

    public String getMockSnapshot() {
        return mockSnapshot;
    }

    public Instant getFirstDetectedAt() {
        return firstDetectedAt;
    }

    public ActionStatus getActionStatus() {
        return actionStatus;
    }

    /** 조치 상태. batch 는 NOT_STARTED 로만 넣는다 */
    public enum ActionStatus {
        NOT_STARTED, REQUESTED, SUCCEEDED, ALREADY_RESOLVED, FAILED
    }
}
