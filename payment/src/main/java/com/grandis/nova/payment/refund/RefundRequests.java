package com.grandis.nova.payment.refund;

import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.domain.exception.ActiveTransactionExistsException;
import com.grandis.nova.payment.domain.exception.PaymentAlreadyRefundedException;
import com.grandis.nova.payment.domain.exception.RefundFailedException;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.PaymentTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

/**
 * 대상의 환불 요청을 받아 REFUND 를 연다(PENDING). 보내는 것은 워커({@link RefundRecovery})다 — 요청 수신과 토스 호출을 떼어,
 * 토스가 느려도 요청 큐가 밀리지 않고 결과 불명 처리가 한 곳에 있게 한다.
 *
 * 요청은 최소 1회 전달이라 같은 요청이 다시 온다. 이미 진행 중 · 이미 환불 · 마지막 환불 확정 실패면 아무것도 열지 않고 성공으로 끝낸다
 * (D14 — 진행 중과 이미 환불의 신호가 달라도 받는 쪽에선 같다). 결과는 처음 확정될 때 아웃박스가 이미 보냈으므로 다시 보내지 않는다.
 * 확정 실패 뒤 다시 열지 않는 것은 결정 33 이다.
 *
 * 열기는 트랜잭션 하나다(원장 예외는 그 경계 밖에서 잡는다).
 */
@Service
public class RefundRequests {

    private static final Logger log = LoggerFactory.getLogger(RefundRequests.class);

    private final PaymentLedger ledger;
    private final TransactionTemplate writeTransaction;

    public RefundRequests(PaymentLedger ledger, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param claimedAmount 요청한 쪽이 아는 금액(주문 총액). 대조만 한다 — 환불은 결제 기록의 금액으로 한다
     * @return 이번에 새로 열었으면 true
     * @throws IllegalStateException 대상에 성공한 결제가 없다 — 요청한 쪽은 결제됐다는데 기록이 없다(데이터 어긋남). 다시 받아도 같다
     */
    public boolean request(PaymentTarget target, BigDecimal claimedAmount) {
        PaymentTransaction opened;
        try {
            opened = writeTransaction.execute(status -> ledger.openRefund(target));
        } catch (ActiveTransactionExistsException e) {
            log.info("환불 요청 — 이미 진행 중이라 열지 않는다 target={}", target);
            return false;
        } catch (PaymentAlreadyRefundedException e) {
            log.info("환불 요청 — 이미 환불돼 열지 않는다 target={}", target);
            return false;
        } catch (RefundFailedException e) {
            log.warn("환불 요청 — 마지막 환불이 확정 실패해 다시 열지 않는다(사람 확인) target={}", target);
            return false;
        } catch (IllegalStateException e) {
            log.error("환불 요청을 처리하지 못했다 — 사람 확인 필요 target={}", target, e);
            throw e;
        }
        if (opened.amount().amount().compareTo(claimedAmount) != 0) {
            log.error("환불 요청 금액이 결제 금액과 다르다 — 결제 금액으로 환불한다 transactionId={} target={} claimed={} amount={}",
                    opened.id(), target, claimedAmount, opened.amount().amount());
        }
        log.info("환불 요청 — 환불을 열었다 transactionId={} target={}", opened.id(), target);
        return true;
    }
}
