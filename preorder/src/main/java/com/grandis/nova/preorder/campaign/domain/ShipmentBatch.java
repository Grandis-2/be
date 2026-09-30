package com.grandis.nova.preorder.campaign.domain;

import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 배송 차수 = 모델 순번의 구간 [positionFrom, positionTo]. positionTo 가 null 이면 상한 없는 마지막 차수다.
 *
 * 오픈 뒤에는 바꾸지 않으므로 updated_at 이 없다(BaseEntity 를 쓰지 않는다).
 * open_ended_marker 는 DB 가 계산하는 칼럼이라 매핑하지 않는다 — 매핑하면 앱이 쓸 수 있는 자리가 생긴다.
 */
@Entity
@Table(name = "shipment_batches")
@EntityListeners(AuditingEntityListener.class)
public class ShipmentBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false)
    private int batchNumber;

    @Column(nullable = false)
    private long positionFrom;

    private Long positionTo;

    @Column(nullable = false)
    private LocalDate estimatedShipStart;

    @Column(nullable = false)
    private LocalDate estimatedShipEnd;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ShipmentBatch() {
    }

    private ShipmentBatch(Long productId, int batchNumber, long positionFrom, Long positionTo,
                          LocalDate estimatedShipStart, LocalDate estimatedShipEnd) {
        this.productId = productId;
        this.batchNumber = batchNumber;
        this.positionFrom = positionFrom;
        this.positionTo = positionTo;
        this.estimatedShipStart = estimatedShipStart;
        this.estimatedShipEnd = estimatedShipEnd;
    }

    /** 오픈 전 설정에서만 만든다. 구간 규칙은 {@link ShipmentBatchPlan} 이 본다. */
    public static ShipmentBatch of(Long productId, int batchNumber, long positionFrom, Long positionTo,
                                   LocalDate estimatedShipStart, LocalDate estimatedShipEnd) {
        return new ShipmentBatch(productId, batchNumber, positionFrom, positionTo,
                estimatedShipStart, estimatedShipEnd);
    }

    public boolean covers(long position) {
        return positionFrom <= position && (positionTo == null || position <= positionTo);
    }

    /** 다른 모듈에 넘기는 읽기 전용 값. */
    public ShipmentBatchSnapshot toSnapshot() {
        return new ShipmentBatchSnapshot(getId(), getBatchNumber(), getPositionFrom(),
                getPositionTo(), getEstimatedShipStart(), getEstimatedShipEnd());
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getBatchNumber() {
        return batchNumber;
    }

    public long getPositionFrom() {
        return positionFrom;
    }

    /** null 이면 상한 없는 마지막 차수. */
    public Long getPositionTo() {
        return positionTo;
    }

    public LocalDate getEstimatedShipStart() {
        return estimatedShipStart;
    }

    public LocalDate getEstimatedShipEnd() {
        return estimatedShipEnd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
