package com.grandis.nova.batch.reconciliation.run;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 검사 실행 한 번(reconciliation_runs). 관리자 실행은 preorder 가 REQUESTED 로 넣고, 정기 실행은 batch 가 RUNNING 으로 넣는다.
 * 대기 · 실행 중 표식(active_marker)은 DB 가 상태로 계산하는 칸이라 매핑하지 않는다.
 * 상태 전이는 엔티티를 고쳐 쓰지 않고 {@link RunRepository} 의 조건부 UPDATE 로 한다 — 그 사이 다른 곳이 바꾼 것을 덮지 않게.
 */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun extends BaseEntity {

    static final int ERROR_MESSAGE_LENGTH = 500;

    /** 관리자 실행을 요청한 관리자(인증 주체 이름). 비어 있으면 정기 실행이다 */
    @Column(updatable = false, length = 64)
    private String requestedBy;

    /** 검사 범위. 비어 있으면 전체, 있으면 그 사전예약 상품 하나 */
    @Column(updatable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RunStatus status;

    private Instant startedAt;

    private Instant cutoffAt;

    private Instant finishedAt;

    @Column(length = ERROR_MESSAGE_LENGTH)
    private String errorMessage;

    private Integer heldCount;

    private Integer pendingSyncCount;

    private Integer cancelingCount;

    private Integer deadLetterCount;

    protected ReconciliationRun() {
    }

    /** 정기 실행. 요청 단계 없이 바로 실행 중으로 들어간다 */
    public static ReconciliationRun scheduled(Instant startedAt, Instant cutoffAt) {
        ReconciliationRun run = new ReconciliationRun();
        run.status = RunStatus.RUNNING;
        run.startedAt = startedAt;
        run.cutoffAt = cutoffAt;
        return run;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public UUID getProductId() {
        return productId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCutoffAt() {
        return cutoffAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getHeldCount() {
        return heldCount;
    }

    public Integer getPendingSyncCount() {
        return pendingSyncCount;
    }

    public Integer getCancelingCount() {
        return cancelingCount;
    }

    public Integer getDeadLetterCount() {
        return deadLetterCount;
    }
}
