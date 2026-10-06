package com.grandis.nova.payment.domain.exception;

/**
 * 결과를 반영하려는데 리스를 잃었다(만료 · 다른 작업자가 다시 선점). 반영은 0행이고, 이 예외로 호출자의 트랜잭션은
 * rollback-only 가 된다 — 같은 트랜잭션에서 먼저 한 업무 변경(아웃박스 기록 등)도 함께 되돌아간다.
 *
 * 늘 이 예외로 알린다. 반환값으로 알리면 무시할 수 있고, 무시하면 거래는 PROCESSING 인데 업무 변경만 커밋된다.
 * 결과는 잃지 않는다 — 리스를 쥔 작업자(또는 만료 뒤 복구 워커)가 같은 멱등 키로 다시 보내 확정한다.
 *
 * 받는 쪽: 트랜잭션 경계 밖에서 잡는다(안에서 잡고 계속하면 커밋 때 UnexpectedRollbackException). 재시도하지 않는다 —
 * 리스를 잃은 쪽이 다시 반영할 길은 없다. 로그만 남기고 끝낸다.
 */
public class LeaseLostException extends RuntimeException {

    private final Long transactionId;

    public LeaseLostException(Long transactionId) {
        super("결제 거래의 리스를 잃어 결과를 반영하지 않았다: transactionId=" + transactionId);
        this.transactionId = transactionId;
    }

    public Long getTransactionId() {
        return transactionId;
    }
}
