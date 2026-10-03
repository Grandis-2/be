package com.grandis.nova.payment.prepare;

import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 준비: 결제창을 열 CAPTURE(PENDING, 새 결제사 주문 번호 · 멱등 키)를 만든다. 결제사를 부르지 않는다.
 *
 * 대상이 결제할 수 있는 상태인지 · 요청한 사용자가 주인인지는 호출자(order · draw)가 확인하고 부른다 — 결제는 대상의 상태를
 * 모른다. 금액도 호출자가 자기 저장값으로 준다. 같은 대상에 PENDING 은 여럿일 수 있다 — 결제창을 다시 열 때마다 새로 만들고,
 * 버려진 것은 그대로 둔다. 대상당 하나로 막는 것은 승인 시작(start)이다.
 *
 * 거래 하나 = 트랜잭션 하나(원장 계약). 트랜잭션은 여기서 연다 — 바깥 트랜잭션에 참여하면 원장 예외가 바깥 작업까지
 * rollback-only 로 만든다. 그래서 들어올 때 확인한다.
 */
@Service
public class PreparePaymentService {

    private final PaymentLedger ledger;
    private final TransactionTemplate writeTransaction;

    public PreparePaymentService(PaymentLedger ledger, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public PaymentTransaction open(PaymentTarget target, Money amount) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 준비는 트랜잭션 밖에서 불러야 한다 — 거래 하나가 트랜잭션 하나다");
        }
        return writeTransaction.execute(status -> ledger.openCapture(target, amount));
    }
}
