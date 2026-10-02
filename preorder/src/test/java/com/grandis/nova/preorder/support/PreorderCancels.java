package com.grandis.nova.preorder.support;

import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderTrigger;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 테스트에서 예약을 실제로 취소한다. 상태를 SQL 로 직접 바꾸지 않는다 —
 * 그러면 이력도 활성 표식 계산도 없는, 정상 경로로는 생길 수 없는 행이 만들어진다.
 */
public class PreorderCancels {

    private final PreorderLedger ledger;
    private final TransactionTemplate transactionTemplate;

    public PreorderCancels(PreorderLedger ledger, TransactionTemplate transactionTemplate) {
        this.ledger = ledger;
        this.transactionTemplate = transactionTemplate;
    }

    /** 취소 요청 → 취소 완료. 상태 머신을 그대로 거친다. */
    public void complete(Long preorderId) {
        transactionTemplate.executeWithoutResult(status -> {
            ledger.fire(preorderId, PreorderTrigger.CANCEL_REQUESTED, EventActor.USER, null);
            ledger.fire(preorderId, PreorderTrigger.CANCEL_COMPLETED, EventActor.SYSTEM, null);
        });
    }
}
