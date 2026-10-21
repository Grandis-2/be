package com.grandis.nova.preorder.cancel;

import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.LockedPreorder;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 결제 기한 만료 취소. 잠근 뒤 결제 가능 · 기한 경과 · 주문 쪽 거절 이력을 다시 확인하고, 주문 사전 확인 없이 시작한다. */
@Service
public class ExpiryCancelService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCancelService.class);

    private final PreorderLedger ledger;
    private final Preorders preorders;
    private final CancelStarter cancelStarter;
    private final Clock clock;

    public ExpiryCancelService(PreorderLedger ledger, Preorders preorders, CancelStarter cancelStarter, Clock clock) {
        this.ledger = ledger;
        this.preorders = preorders;
        this.cancelStarter = cancelStarter;
        this.clock = clock;
    }

    /** @return 이번에 취소를 시작했으면 true. 대상이 아니면(결제 · 취소됨 · 기한 전 · 주문 쪽 거절 이력) false */
    @Transactional
    public boolean expire(String preorderToken) {
        LockedPreorder preorder = ledger.lockByToken(preorderToken)
                .orElseThrow(() -> new IllegalArgumentException("예약이 없다: " + preorderToken));
        Instant dueAt = preorder.paymentDueAt();
        if (preorder.status() != PreorderStatus.REGISTERED || clock.instant().isBefore(dueAt)) {
            log.info("만료 대상이 아니라 무시한다 preorderId={} status={}", preorderToken, preorder.status());
            return false;
        }
        // 결제됐는데 결제 확인을 못 받은 예약이면 만료 → 거절이 되풀이된다. 후보를 고른 뒤 잠그기 전에 거절됐을 수도 있어
        // 잠근 뒤 다시 본다(거절 복귀도 이 행을 잠근다)
        if (preorders.wasCancelRejectedToRegistered(preorder.id())) {
            log.warn("주문 쪽에서 취소가 거절된 적이 있어 만료하지 않는다 preorderId={}", preorderToken);
            return false;
        }
        return cancelStarter.start(preorder, EventActor.SYSTEM, null, CancelReason.EXPIRY).applied();
    }
}
