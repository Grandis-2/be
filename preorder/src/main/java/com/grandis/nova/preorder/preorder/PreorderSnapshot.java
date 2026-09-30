package com.grandis.nova.preorder.preorder;

import com.grandis.nova.preorder.preorder.domain.Preorder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 모듈 밖에 내주는 예약 값. 엔티티는 이 모듈 안에만 두고, 상태를 바꾸는 일은 {@link PreorderLedger} 로만 한다.
 * 상품명 · 옵션명 · 가격은 접수 시점에 고정한 값이다.
 */
public record PreorderSnapshot(
        Long id,
        String preorderToken,
        Long customerId,
        Long productId,
        Long optionId,
        Long shipmentBatchId,
        long queuePosition,
        String admissionTicketId,
        String idempotencyKey,
        String productTitle,
        String optionTitle,
        BigDecimal unitPrice,
        PreorderStatus status,
        Instant payableFrom,
        String externalReference,
        String internalNote,
        long eventSequence,
        Instant createdAt,
        Instant updatedAt
) {

    public static final Duration PAYMENT_WINDOW = Duration.ofHours(24);

    public static PreorderSnapshot of(Preorder preorder) {
        return new PreorderSnapshot(preorder.getId(), preorder.getPreorderToken(), preorder.getCustomerId(),
                preorder.getProductId(), preorder.getOptionId(), preorder.getShipmentBatchId(),
                preorder.getQueuePosition(), preorder.getAdmissionTicketId(), preorder.getIdempotencyKey(),
                preorder.getProductTitleSnapshot(), preorder.getOptionTitleSnapshot(),
                preorder.getUnitPriceSnapshot(), preorder.getStatus(), preorder.getPayableFrom(),
                preorder.getExternalReference(), preorder.getInternalNote(), preorder.getEventSequence(),
                preorder.getCreatedAt(), preorder.getUpdatedAt());
    }

    /** 결제 기한 — 결제 가능해진 시각부터 24시간. 연장은 없다. PAYABLE 이 아니면 없다. */
    public Instant paymentDueAt() {
        return status == PreorderStatus.PAYABLE && payableFrom != null ? payableFrom.plus(PAYMENT_WINDOW) : null;
    }

    /** 지금 결제할 수 없으면 그 까닭, 결제할 수 있으면 null. 기한이 지났는데 아직 만료 처리 전이면 DUE_PASSED 다. */
    public PayabilityBlocker payabilityBlocker(Instant now) {
        return switch (status) {
            case PENDING_SYNC -> PayabilityBlocker.NOT_YET_REGISTERED;
            case PAYABLE -> now.isBefore(paymentDueAt()) ? null : PayabilityBlocker.DUE_PASSED;
            case CANCELING -> PayabilityBlocker.CANCELING;
            case CANCELED -> PayabilityBlocker.CANCELED;
        };
    }

    /** 취소 버튼을 보일지. 배송 시작 여부는 취소 요청 때 order 에 다시 묻는다. */
    public boolean isCancelable() {
        return status == PreorderStatus.PENDING_SYNC || status == PreorderStatus.PAYABLE;
    }
}
