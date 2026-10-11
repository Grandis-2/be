package com.grandis.nova.preorder.preorder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 모듈 밖에 내주는 예약 값. 엔티티는 이 모듈 안에만 두고, 상태를 바꾸는 일은 {@link PreorderLedger} 로만 한다.
 * 상품명 · 옵션명 · 가격은 접수 시점에 고정한 값이다.
 */
public record PreorderSnapshot(
        UUID id,
        String preorderToken,
        UUID customerId,
        UUID productId,
        UUID optionId,
        UUID shipmentBatchId,
        long queuePosition,
        String admissionTicketId,
        String idempotencyKey,
        String productTitle,
        String optionTitle,
        BigDecimal unitPrice,
        PreorderStatus status,
        Instant payableFrom,
        Instant paymentStartedAt,
        Instant reservedAt,
        String externalReference,
        String internalNote,
        long eventSequence,
        Instant createdAt,
        Instant updatedAt
) {

    public static final Duration PAYMENT_WINDOW = Duration.ofHours(24);

    /** 결제 기한 — 결제 가능해진 시각부터 24시간. 연장은 없다. REGISTERED 이 아니면 없다. */
    public Instant paymentDueAt() {
        return status == PreorderStatus.REGISTERED && payableFrom != null ? payableFrom.plus(PAYMENT_WINDOW) : null;
    }

    /** 지금 결제할 수 없으면 그 까닭, 결제할 수 있으면 null. 기한이 지났는데 아직 만료 처리 전이면 DUE_PASSED 다. */
    public PayabilityBlocker payabilityBlocker(Instant now) {
        return switch (status) {
            case PENDING_SYNC -> PayabilityBlocker.NOT_YET_REGISTERED;
            case REGISTERED -> now.isBefore(paymentDueAt()) ? null : PayabilityBlocker.DUE_PASSED;
            case RESERVED -> PayabilityBlocker.ALREADY_RESERVED;
            case CANCELING -> PayabilityBlocker.CANCELING;
            case CANCELED -> PayabilityBlocker.CANCELED;
        };
    }

    /** 취소 버튼을 보일지. 결제된 예약도 취소(환불)할 수 있다 — 배송 시작 여부는 취소 요청 때 order 에 다시 묻는다. */
    public boolean isCancelable() {
        return status == PreorderStatus.PENDING_SYNC || status == PreorderStatus.REGISTERED
                || status == PreorderStatus.RESERVED;
    }
}
