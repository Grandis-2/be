package com.grandis.nova.payment.domain.repository;

import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.exception.ActiveTransactionExistsException;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.LeaseToken;
import com.grandis.nova.payment.vo.ProviderError;
import com.grandis.nova.payment.vo.ProviderPaymentKey;

import java.time.Duration;
import java.time.Instant;

/**
 * 결제 거래 쓰기 포트. {@link com.grandis.nova.payment.PaymentLedger} 만 쓴다(PaymentArchitectureTest) —
 * 전이 판정(상태 머신)을 건너뛴 UPDATE 를 막는다.
 *
 * 상태를 바꾸는 쓰기는 모두 조건부 UPDATE 이고 바뀐 행 수(0 또는 1)를 돌려준다. 0 은 "그사이 누가 먼저 바꿨다" 다.
 * 리스 만료 · 재시도 시각은 DB 시각(UTC_TIMESTAMP(6))으로 쓰고 비교한다. now 로 받는 시각은 기록용(requested_at · finished_at)이다.
 */
public interface PaymentTransactionWriter {

    /**
     * 새 PENDING 거래를 저장하고 id 가 채워진 거래를 돌려준다.
     *
     * @throws ActiveTransactionExistsException REFUND 인데 그 대상에 진행 중 · 성공한 REFUND 가 이미 있다.
     *                                          같은 대상에 셋 이상이 겹치고 앞선 트랜잭션이 롤백되면, 기다리던 쪽 하나는 이 예외가
     *                                          아니라 교착(PessimisticLockingFailureException 계열)을 받을 수 있다 — 번역하지 않는다
     */
    PaymentTransaction insert(PaymentTransaction transaction);

    /**
     * CAPTURE 시작: PENDING · 리스 없음일 때만 PROCESSING 으로, 결제 키 · 보낸 시각 · 새 리스를 적고 보낸 횟수를 1 올린다.
     *
     * @param seen 시작할 거래의 스냅샷(id · 대상)
     * @throws ActiveTransactionExistsException 그 대상에 진행 중 · 성공한 CAPTURE 가 이미 있다. 같은 대상에 셋 이상이 겹치고
     *                                          앞선 트랜잭션이 롤백되면 기다리던 쪽 하나는 교착 예외를 받을 수 있다(번역하지 않음,
     *                                          돈에는 영향 없음 — 처리는 부르는 유스케이스)
     */
    int start(PaymentTransaction seen, ProviderPaymentKey paymentKey, LeaseToken lease, Duration leaseFor, Instant now);

    /**
     * 워커 선점: 읽은 상태 · 리스 표식이 그대로이고 준비됐을 때만(REFUND PENDING · 재시도 시각이 된 RETRY_SCHEDULED ·
     * 리스가 만료된 PROCESSING) PROCESSING 으로, 새 리스를 적고 보낸 횟수를 1 올린다.
     *
     * @param seenLease 읽은 스냅샷의 리스 표식. 없으면 null
     */
    int claim(Long transactionId, TransactionStatus seenStatus, LeaseToken seenLease, LeaseToken lease,
              Duration leaseFor, Instant now);

    /**
     * 끝내기(SUCCEEDED · FAILED). 리스를 풀고 끝난 시각을 적는다. 마지막 오류는 error 로 바꾼다 — 성공(null)이면 지운다.
     * 성공한 거래에 앞선 시도의 오류가 남으면 "마지막 시도 결과" 를 보여 줄 때 실패로 읽힌다.
     */
    int finish(Long transactionId, LeaseToken lease, TransactionStatus to, ProviderError error, Instant now);

    /** RETRY_SCHEDULED 로. 리스를 풀고 재시도 시각을 DB 시각 + delay 로 적는다. */
    int reschedule(Long transactionId, LeaseToken lease, ProviderError error, Duration delay);

    /** 상태 · 리스는 그대로 두고 마지막 오류만 적는다(결과 불명). */
    int recordError(Long transactionId, LeaseToken lease, ProviderError error);
}
