package com.grandis.nova.payment.recovery;

import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.exception.LeaseLostException;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 결과를 확정하지 못한 거래를 집어 유형별 처리기({@link RecoveryHandler})에 넘긴다. 대상은 원장 claim 의 준비 조건과 같다 —
 * 재시도 시각이 된 RETRY_SCHEDULED, 리스가 만료된 PROCESSING(응답을 잃었거나 반영 전에 멈췄다), REFUND PENDING. 에스컬레이션된 거래는 집지 않는다.
 *
 * 한 건씩 한다: [tx] 후보 하나를 SKIP LOCKED 로 잠가 읽고 같은 트랜잭션에서 claim(리스) → 커밋 → 처리기(결제사 호출 · 반영).
 * 인스턴스 여럿이 돌아도 잠긴 후보는 건너뛰므로 같은 행을 다투지 않고, 선점은 조건부 UPDATE 라 겹쳐도 한쪽만 리스를 쥔다.
 * 리스를 쥔 즉시 결제사를 부른다 — 여러 건을 먼저 잡아 두면 뒤쪽 건이 리스(70초)를 기다리며 쓴다.
 *
 * 처리기의 예외는 그 건에서 멈춘다. 리스가 끝나면 다음 실행이 다시 집는다(처리기의 시각 상한이 끝을 낸다).
 * 종료가 시작되면 새 건을 집지 않는다 — 처리 중인 한 건만 마친다.
 */
@Component
public class RecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(RecoveryWorker.class);

    /** 유형당 한 번에 처리하는 최대 건수. 건마다 결제사 호출(최악 63초)이 있어 실행 한 번의 길이를 묶는다. */
    static final int BATCH = 20;

    private final Map<TransactionType, RecoveryHandler> handlers;
    private final PaymentTransactionReader reader;
    private final PaymentLedger ledger;
    private final TransactionTemplate writeTransaction;
    private volatile boolean stopping;

    public RecoveryWorker(List<RecoveryHandler> handlers, PaymentTransactionReader reader, PaymentLedger ledger,
                          PlatformTransactionManager transactionManager) {
        this.handlers = new EnumMap<>(TransactionType.class);
        for (RecoveryHandler handler : handlers) {
            if (this.handlers.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("한 유형에 복구 처리기가 둘이다: " + handler.type());
            }
        }
        this.reader = reader;
        this.ledger = ledger;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /** @return 처리기에 넘긴 건수 */
    public int recoverDue() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("복구는 트랜잭션 밖에서 돌려야 한다 — 결제사 호출 동안 잠금을 쥐지 않게");
        }
        int handled = 0;
        for (RecoveryHandler handler : handlers.values()) {
            for (int i = 0; i < BATCH && !stopping; i++) {
                Optional<Claim> next = claimNext(handler.type());
                if (next.isEmpty()) {
                    break;
                }
                recover(handler, next.get());
                handled++;
            }
        }
        return handled;
    }

    /** 컨텍스트 종료가 시작될 때(스케줄러 종료 전) 불린다. */
    @EventListener(ContextClosedEvent.class)
    public void stop() {
        stopping = true;
    }

    private Optional<Claim> claimNext(TransactionType type) {
        return writeTransaction.execute(status -> reader.lockNextRecoverable(type)
                .flatMap(seen -> ledger.claim(seen).map(claimed -> new Claim(seen, claimed))));
    }

    private static void recover(RecoveryHandler handler, Claim claim) {
        Long id = claim.seen().id();
        try {
            handler.recover(claim.seen(), claim.claimed());
        } catch (LeaseLostException e) {
            // 처리가 리스(70초)보다 오래 걸려 다른 작업자가 집어 갔다. 결과는 리스를 쥔 쪽이 확정한다
            log.warn("결제 복구 반영 실패 — 리스를 잃음 transactionId={}", id);
        } catch (RuntimeException e) {
            log.error("결제 복구 실패 — 리스가 끝나면 다시 집는다 transactionId={}", id, e);
        }
    }

    private record Claim(PaymentTransaction seen, ClaimedTransaction claimed) {
    }
}
