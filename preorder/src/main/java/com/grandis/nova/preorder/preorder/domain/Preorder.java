package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.common.BaseEntity;
import com.grandis.nova.preorder.preorder.NewPreorder;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 예약. 수량은 항상 1이라 칸이 없다.
 *
 * 상태 · 이력 번호 · 결제 가능 시각은 setter 가 없다. 바꾸는 길은 {@link PreorderLedger} 하나뿐이고,
 * 거기서 "현재 상태를 조건으로 한 UPDATE + 이력 INSERT" 를 한 트랜잭션으로 한다.
 * 그래서 이 엔티티의 칼럼은 INSERT 뒤에 JPA 변경 감지로 바뀌지 않게 막아 두었다(updatable = false).
 *
 * active_marker 는 status 에서 DB 가 계산하는 생성 칼럼이라 매핑하지 않는다.
 * 앱이 이 칸에 값을 쓰면 MySQL 이 거부하고, 매핑해 두면 그 실수를 할 자리가 생긴다.
 */
@Entity
@Table(name = "preorders")
public class Preorder extends BaseEntity {

    /** 결제 기한. payable_from 부터 이만큼이다(ERD: 연장 없음). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 36)
    private String preorderToken;

    @Column(nullable = false, updatable = false)
    private Long customerId;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false)
    private Long optionId;

    @Column(nullable = false, updatable = false)
    private Long shipmentBatchId;

    @Column(nullable = false, updatable = false)
    private long queuePosition;

    @Column(updatable = false, length = 64)
    private String admissionTicketId;

    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false, length = 100)
    private String productTitleSnapshot;

    @Column(nullable = false, updatable = false, length = 120)
    private String optionTitleSnapshot;

    @Column(nullable = false, updatable = false, precision = 12, scale = 0)
    private BigDecimal unitPriceSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PreorderStatus status;

    @Column(updatable = false)
    private Instant payableFrom;

    @Column(updatable = false)
    private Instant paymentStartedAt;

    @Column(updatable = false)
    private Instant reservedAt;

    @Column(updatable = false, length = 100)
    private String externalReference;

    @Column(columnDefinition = "text")
    private String internalNote;

    @Column(nullable = false, updatable = false)
    private long eventSequence;

    protected Preorder() {
    }

    /** 접수 직후 상태. 첫 이력(번호 1)과 함께 {@link PreorderLedger#accept} 가 저장한다. */
    public Preorder(NewPreorder draft) {
        this.preorderToken = draft.preorderToken();
        this.customerId = draft.customerId();
        this.productId = draft.productId();
        this.optionId = draft.optionId();
        this.shipmentBatchId = draft.shipmentBatchId();
        this.queuePosition = draft.queuePosition();
        this.admissionTicketId = draft.admissionTicketId();
        this.idempotencyKey = draft.idempotencyKey();
        this.productTitleSnapshot = draft.productTitleSnapshot();
        this.optionTitleSnapshot = draft.optionTitleSnapshot();
        this.unitPriceSnapshot = draft.unitPriceSnapshot();
        this.internalNote = draft.internalNote();
        this.status = PreorderStatus.PENDING_SYNC;
        this.eventSequence = PreorderEvent.FIRST_SEQUENCE;
    }

    /** 관리자 전용 메모. 이력을 남기지 않는다. */
    public void changeInternalNote(String internalNote) {
        this.internalNote = internalNote;
    }

    /** 다른 모듈에 넘기는 읽기 전용 값. */
    public PreorderSnapshot toSnapshot() {
        return new PreorderSnapshot(getId(), getPreorderToken(), getCustomerId(),
                getProductId(), getOptionId(), getShipmentBatchId(),
                getQueuePosition(), getAdmissionTicketId(), getIdempotencyKey(),
                getProductTitleSnapshot(), getOptionTitleSnapshot(),
                getUnitPriceSnapshot(), getStatus(), getPayableFrom(), getPaymentStartedAt(), getReservedAt(),
                getExternalReference(), getInternalNote(), getEventSequence(),
                getCreatedAt(), getUpdatedAt());
    }

    public Long getId() {
        return id;
    }

    public String getPreorderToken() {
        return preorderToken;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getOptionId() {
        return optionId;
    }

    public Long getShipmentBatchId() {
        return shipmentBatchId;
    }

    public long getQueuePosition() {
        return queuePosition;
    }

    public String getAdmissionTicketId() {
        return admissionTicketId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProductTitleSnapshot() {
        return productTitleSnapshot;
    }

    public String getOptionTitleSnapshot() {
        return optionTitleSnapshot;
    }

    public BigDecimal getUnitPriceSnapshot() {
        return unitPriceSnapshot;
    }

    public PreorderStatus getStatus() {
        return status;
    }

    public Instant getPayableFrom() {
        return payableFrom;
    }

    public Instant getPaymentStartedAt() {
        return paymentStartedAt;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getInternalNote() {
        return internalNote;
    }

    public long getEventSequence() {
        return eventSequence;
    }
}
