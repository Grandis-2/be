package com.grandis.nova.payment.domain.repository;

import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderOrderId;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 결제 거래 읽기 포트. 읽은 스냅샷으로 전이를 정하지 않는다 — 전이는 원장이 스냅샷의 상태 · 리스를 조건으로 한 UPDATE 로 하므로,
 * 그사이 바뀌었으면 0행이 된다. 그래서 잠그지 않는다. 예외는 {@link #lockNextRecoverable} 하나로, 정확성이 아니라 인스턴스끼리
 * 같은 후보를 다투지 않게 하려는 잠금이다.
 */
public interface PaymentTransactionReader {

    Optional<PaymentTransaction> findById(UUID transactionId);

    /** 결제창이 돌려준 결제사 주문 번호로 찾는다. CAPTURE 만 이 번호가 있다. */
    Optional<PaymentTransaction> findByProviderOrderId(ProviderOrderId providerOrderId);

    /**
     * 대상의 거래, 만든 순(created_at, 같으면 id). uq_payment_tx_active 의 앞부분(대상)으로 찾고 대상별 몇 행만 정렬한다.
     * 둘 다 앱 시계라 서버 시계 차보다 가까이 만든 두 행은 뒤바뀔 수 있다. 마지막 REFUND 판정에는 문제없다 — 한 대상의 REFUND 는
     * 앞 것이 끝나야 다음이 열린다(uq_payment_tx_active).
     */
    List<PaymentTransaction> findTransactionsByTarget(PaymentTarget target);

    /**
     * 만료할 CAPTURE 후보: 한 번도 보내지 않았고(PENDING) 연 지 openedFor 가 지났다(DB 시각 기준). 잠그지 않는다 —
     * 만료는 조건부 UPDATE 라 그사이 시작된 행은 0행이 된다. 오래된 것부터 limit 건.
     */
    List<PaymentTransaction> findExpirableCaptures(Duration openedFor, int limit);

    /**
     * 복구할 다음 거래 하나를 잠가 읽는다(FOR UPDATE SKIP LOCKED) — 다른 인스턴스가 잠근 행은 건너뛴다. 이 읽기만 잠근다.
     * 호출자의 트랜잭션 안에서 부르고, 같은 트랜잭션에서 원장 claim 으로 선점한다. 에스컬레이션된 거래는 고르지 않는다.
     * 고르는 조건은 claim 의 준비 조건과 같다(재시도 시각이 된 RETRY_SCHEDULED · 리스가 만료된 PROCESSING · REFUND PENDING).
     */
    Optional<PaymentTransaction> lockNextRecoverable(TransactionType type);
}
