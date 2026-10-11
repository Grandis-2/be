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
 * 외부 등록 · 취소 작업의 원장. 행은 preorder 가 만들고 실행은 worker 가 한다.
 *
 * preorder 의 쓰기 범위는 INSERT(PENDING)와 REGISTER 무효화(→ CANCELED)뿐이다.
 * 그래서 실행 칸(lease_token · lease_expires_at · dead_lettered_at)은 읽기 전용으로 매핑하고,
 * status 도 변경 감지로는 바뀌지 않게 막았다 — 무효화는 {@link PreorderSyncJobRepository#cancelRegister} 로만 한다.
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
        return new SyncJobSnapshot(getId(), getPreorderId(), getJobType(), getStatus(),
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
