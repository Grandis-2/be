package com.grandis.nova.payment.recovery;

import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.model.PaymentTransaction;

/**
 * 한 거래 유형의 복구. {@link RecoveryWorker} 가 선점한 거래를 넘긴다 — 결제사에 다시 보내거나 조회해 결과를 반영하는 것은 여기서 한다.
 * 유형마다 하나다(CAPTURE = 승인 복구, REFUND = 환불 실행 · 복구는 NV-103 이 더한다). 처리기가 없는 유형은 워커가 집지 않는다.
 */
public interface RecoveryHandler {

    TransactionType type();

    /**
     * 트랜잭션 밖에서 불린다(결제사 호출이 들어 있다). 결과 반영은 처리기가 거래 하나 = 트랜잭션 하나로 한다.
     *
     * @param seen    선점 직전의 스냅샷 — 직전 상태 · 마지막 오류로 푸는 방법을 고른다
     * @param claimed 리스를 쥔 거래
     */
    void recover(PaymentTransaction seen, ClaimedTransaction claimed);
}
