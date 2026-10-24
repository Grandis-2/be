package com.grandis.nova.payment.confirm;

import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.exception.ActiveTransactionExistsException;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderOrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * 주문이 승인 중으로 바뀌었다는 알림(ORDER_PAYMENT_AUTHORIZING)을 받아, 승인 요청이 닿지 않은 결제창을 복구에 넘긴다.
 *
 * order 는 결제창을 확인(확보 · 결제 키 저장)한 뒤 승인 중으로 바꾸고 승인을 부른다. 그 승인 요청이 payment 에 닿지 않으면 거래는 시작 전으로
 * 남아 만료({@link PendingCaptureExpiry})가 주문을 결제 대기로 돌린다 — 고객은 인증을 마쳤는데. 그래서 확보 뒤 {@link #DIRECT_FIRST} 가
 * 지나도 시작 전이면 넘긴다({@link PaymentLedger#handOver}). 넘긴 거래는 복구({@link CaptureRecovery})가 확보 때의 키로 첫 승인을 보내고
 * 결과를 이벤트로 알린다.
 *
 * - 이미 시작했거나 끝났으면 아무것도 하지 않는다(대개 이렇다). 시작 전 상태로는 돌아오지 않는다.
 * - 이르면 남은 시간만큼 미루라고 돌려준다 — 받는 쪽이 같은 큐에 지연을 두고 다시 보낸다. 그동안 직접 승인 요청이 먼저 시작한다.
 * - 키가 없으면(다른 키가 왔다 · 시작 금지 재요청이 넘김을 껐다) · 결제창 금액이 알림의 주문 총액과 다르면 · 토스 승인 창
 *   ({@link CaptureRecovery#APPROVAL_WINDOW}, 마지막 확보부터 잰다)이 지났으면 넘기지 않는다. 만료가 닫는다. 여기의 판정은 경고 로그 ·
 *   지표용이고, 최종 판정은 넘김 UPDATE 의 조건이다.
 * - 넘김과 직접 승인 요청의 시작 · 넘김 끄기는 같은 PENDING 조건부 UPDATE 라 한쪽만 된다. 토스를 부르지 않는다 — 이 소비기는 환불 요청과
 *   스레드 하나를 쓴다.
 * - 같은 알림을 다시 받아도 결과가 같다(넘긴 뒤에는 시작 전이 아니다). 늦음은 다시 받으면 다시 센다.
 *
 * 알림이 나간 뒤 주문이 결제 대기로 돌아가는 길: 되돌림은 끝난 거래(거절 · 만료)나 시작될 수 없다는 확언(번호 없음 · 금액 불일치 — 넘김도
 * 같은 대상 · 금액을 대조한다)에서만 일어난다. 롤백된 승인 중 전환에는 알림이 없다(order 의 아웃박스가 같은 트랜잭션이다).
 *
 * 한계:
 * - 승인 중 재요청이 결제할 수 없다고 판정해 시작 금지로 물으면 넘김을 끄지만, 그 호출이 payment 에 닿았을 때뿐이다. 닿지 못했거나 판정과
 *   끄기 사이에 넘겨지면 결제할 수 없다고 판정된 주문도 청구될 수 있다. 승인이 늦게 끝나는 경우와 같은 부류로, 청구 뒤에는 order 의 기존
 *   흐름(예약 취소 보류 → 결제 뒤 환불)이 처리한다.
 * - 넘긴 시각이 복구의 첫 전송 시각이라, 토스 세션이 이미 끝난 거래도 복구가 최대 약 15분(승인 창 + 정산 여유) 거절 · 재조회를 되풀이한 뒤
 *   실패로 확정한다.
 * - 결제창을 확인할 때마다 reserved_at 이 새로 잡혀, 인증 뒤 10분이 지난 결제창도 넘겨질 수 있다 — 토스가 거절하므로 오결제는 아니다.
 *
 * 결제 키는 로그에 싣지 않는다.
 */
@Component
public class ConfirmHandover {

    private static final Logger log = LoggerFactory.getLogger(ConfirmHandover.class);

    /** 확보 뒤 이만큼은 직접 승인 요청을 기다린다. order 가 승인 응답을 최대 70초 기다리므로 그보다 길게 둔다. */
    static final Duration DIRECT_FIRST = Duration.ofSeconds(90);

    private final PaymentTransactionReader reader;
    private final PaymentLedger ledger;
    private final UnreachedConfirmMetrics metrics;
    private final TransactionTemplate writeTransaction;

    public ConfirmHandover(PaymentTransactionReader reader, PaymentLedger ledger, UnreachedConfirmMetrics metrics,
                           PlatformTransactionManager transactionManager) {
        this.reader = reader;
        this.ledger = ledger;
        this.metrics = metrics;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param target 알림의 대상(주문). 결제창이 그 대상의 것인지 대조한다
     * @param amount 알림에 실린 주문 총액. 결제창 금액과 다르면 넘기지 않는다
     * @return 다시 볼 때까지 미룰 시간. 비어 있으면 끝났다
     * @throws IllegalStateException    결제창 번호를 모른다 — order 는 확인한 결제창으로만 승인 중이 되므로 계약이 어긋났다
     * @throws IllegalArgumentException 다른 대상의 결제창이다
     */
    public Optional<Duration> handle(PaymentTarget target, ProviderOrderId providerOrderId, Money amount) {
        PaymentTransaction seen = reader.findByProviderOrderId(providerOrderId).orElseThrow(() ->
                new IllegalStateException("승인 중 알림의 결제창이 없다: providerOrderId=" + providerOrderId.value()));
        if (!seen.target().equals(target)) {
            throw new IllegalArgumentException("승인 중 알림의 대상이 결제창과 다르다: transactionId=" + seen.id()
                    + ", target=" + target);
        }
        if (seen.status() != TransactionStatus.PENDING) {
            return Optional.empty();
        }
        if (seen.providerPaymentKey() == null) {
            log.warn("승인 중 알림의 결제창에 결제 키가 없어 넘기지 않음(시작되지 않으면 만료가 닫는다) transactionId={} orderId={}",
                    seen.id(), providerOrderId.value());
            return Optional.empty();
        }
        if (!seen.amount().equals(amount)) {
            log.warn("승인 중 알림의 주문 총액이 결제창 금액과 달라 넘기지 않음(시작되지 않으면 만료가 닫는다) transactionId={} orderId={}",
                    seen.id(), providerOrderId.value());
            return Optional.empty();
        }
        Duration since = reader.sinceReserved(seen.id())
                .orElseThrow(() -> new IllegalStateException("거래가 사라졌다: " + seen.id()));
        if (since.compareTo(DIRECT_FIRST) < 0) {
            return Optional.of(DIRECT_FIRST.minus(since));
        }
        if (since.compareTo(CaptureRecovery.APPROVAL_WINDOW) >= 0) {
            metrics.record(UnreachedConfirmMetrics.Result.TOO_LATE);
            log.warn("승인 요청이 닿지 않은 결제창 — 승인 창이 지나 넘기지 않음(만료가 닫는다) transactionId={} orderId={} since={}",
                    seen.id(), providerOrderId.value(), since);
            return Optional.empty();
        }
        boolean handed;
        try {
            handed = Boolean.TRUE.equals(writeTransaction.execute(status ->
                    ledger.handOver(seen, amount, DIRECT_FIRST, CaptureRecovery.APPROVAL_WINDOW)));
        } catch (ActiveTransactionExistsException e) {
            log.info("승인 요청이 닿지 않은 결제창 — 대상에 진행 중 · 성공한 거래가 있어 넘기지 않음 transactionId={} target={}",
                    seen.id(), target);
            return Optional.empty();
        }
        if (handed) {
            metrics.record(UnreachedConfirmMetrics.Result.HANDED_OVER);
            log.warn("승인 요청이 닿지 않은 결제창 — 복구에 넘김 transactionId={} orderId={} since={}",
                    seen.id(), providerOrderId.value(), since);
        } else {
            log.info("승인 요청이 닿지 않은 결제창 — 그사이 시작 · 만료 · 다시 확보 · 넘김 꺼짐(키 지움)이라 넘기지 않음 transactionId={}", seen.id());
        }
        return Optional.empty();
    }
}
