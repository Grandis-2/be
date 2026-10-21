package com.grandis.nova.preorder.preorder;

import java.time.Instant;
import java.util.UUID;

/**
 * 잠그며 읽은 예약 값({@link PreorderLedger#lock}). 전이를 판정하고 이력 순번을 정하는 데 필요한 칸만 담는다 — 잠근 뒤
 * 다시 읽지 않고 이 값으로 전이한다. 전이 뒤에는 옛 값이므로 다시 넘기지 않는다.
 *
 * @param eventSequence 잠글 때의 이력 순번
 */
public record LockedPreorder(
        UUID id,
        String preorderToken,
        UUID customerId,
        PreorderStatus status,
        Instant payableFrom,
        Instant reservedAt,
        String externalReference,
        long eventSequence
) {

    /** 이번 전이가 이력을 남기면 받는 순번. 상태를 바꾸는 UPDATE 만 순번을 하나 올린다. */
    public long nextEventSequence() {
        return eventSequence + 1;
    }

    /** 결제 기한. {@link PreorderSnapshot#paymentDueAt} 과 같은 규칙이다. */
    public Instant paymentDueAt() {
        return status == PreorderStatus.REGISTERED && payableFrom != null
                ? payableFrom.plus(PreorderSnapshot.PAYMENT_WINDOW) : null;
    }
}
