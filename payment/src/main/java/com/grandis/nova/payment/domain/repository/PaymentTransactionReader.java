package com.grandis.nova.payment.domain.repository;

import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderOrderId;

import java.util.List;
import java.util.Optional;

/**
 * 결제 거래 읽기 포트. 잠그지 않고 읽는다 — 읽은 스냅샷으로 전이를 정하지 않는다. 전이는 원장이 스냅샷의 상태 · 리스를
 * 조건으로 한 UPDATE 로 하므로, 그사이 바뀌었으면 0행이 된다.
 */
public interface PaymentTransactionReader {

    Optional<PaymentTransaction> findById(Long transactionId);

    /** 결제창이 돌려준 결제사 주문 번호로 찾는다. CAPTURE 만 이 번호가 있다. */
    Optional<PaymentTransaction> findByProviderOrderId(ProviderOrderId providerOrderId);

    /**
     * 대상의 거래, 만든 순(id 오름차순). uq_payment_tx_active 의 앞부분(대상)으로 찾고 대상별 몇 행만 정렬한다(EXPLAIN 실측).
     * created_at 으로 정렬하지 않는다 — 앱 시계라 서버마다 시계가 달라 "마지막 시도" 가 뒤바뀔 수 있다. id 는 DB 가 순서대로 매긴다.
     */
    List<PaymentTransaction> findTransactionsByTarget(PaymentTarget target);
}
