package com.grandis.nova.preorder.syncjob.domain;

import com.grandis.nova.common.BaseEntity;
import com.grandis.nova.preorder.syncjob.SyncJobSnapshot;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 외부 등록 · 취소 작업의 원장. 행은 preorder 만 쓴다 — 실행은 worker 가 하고, 결과는 이벤트로 받아 여기에 남긴다.
 *
 * 상태는 변경 감지로 바뀌지 않게 막았다 — 전이는 {@link PreorderSyncJobRepository} 의 조건부 UPDATE 로만 한다
 * (순서 없이 · 두 번 오는 결과 이벤트가 끝난 작업을 되돌리지 못하게). 리스 칸(lease_token · lease_expires_at)은 쓰지 않는다.
 */
@Entity
@Table(name = "preorder_sync_jobs")
public class PreorderSyncJob extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private UUID preorderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private SyncJobType jobType;

    /** 접수 때 고정한 전송 내용. 재시도마다 같은 내용을 보내야 외부가 중복으로 본다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String requestPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SyncJobStatus status;

    /** 몇 번째 실행인가. 관리자 재처리마다 1 씩 오른다 — 지난 실행의 결과가 늦게 와도 작업 상태를 바꾸지 못하게 가린다. */
    @Column(nullable = false, updatable = false)
    private int run;

    @Column(insertable = false, updatable = false, length = 64)
    private String leaseToken;

    @Column(insertable = false, updatable = false)
    private Instant leaseExpiresAt;

    @Column(insertable = false, updatable = false)
    private Instant deadLetteredAt;

    protected PreorderSyncJob() {
    }

    private PreorderSyncJob(UUID preorderId, SyncJobType jobType, String requestPayload) {
        this.preorderId = preorderId;
        this.jobType = jobType;
        this.requestPayload = requestPayload;
        this.status = SyncJobStatus.PENDING;
        this.run = 1;
    }

    /** 접수 트랜잭션에서 만든다. */
    public static PreorderSyncJob register(UUID preorderId, String requestPayload) {
        return new PreorderSyncJob(preorderId, SyncJobType.REGISTER, requestPayload);
    }

    /** 주문 정리가 끝난 뒤(PREORDER_ORDER_SETTLED) 만든다. */
    public static PreorderSyncJob cancel(UUID preorderId, String requestPayload) {
        return new PreorderSyncJob(preorderId, SyncJobType.CANCEL, requestPayload);
    }

    /** 다른 모듈에 넘기는 읽기 전용 값. */
    public SyncJobSnapshot toSnapshot() {
        return new SyncJobSnapshot(getId(), getPreorderId(), getJobType(), getStatus(), getRun(),
                getRequestPayload(), getLeaseExpiresAt(), getDeadLetteredAt(), getCreatedAt(),
                getUpdatedAt());
    }

    public UUID getPreorderId() {
        return preorderId;
    }

    public SyncJobType getJobType() {
        return jobType;
    }

    public String getRequestPayload() {
        return requestPayload;
    }

    public SyncJobStatus getStatus() {
        return status;
    }

    public int getRun() {
        return run;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getDeadLetteredAt() {
        return deadLetteredAt;
    }
}
