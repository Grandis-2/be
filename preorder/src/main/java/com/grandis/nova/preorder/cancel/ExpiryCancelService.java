package com.grandis.nova.preorder.cancel;

import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 결제 기한 만료 취소. 잠근 뒤 결제 가능 · 기한 경과를 다시 확인하고, 주문 사전 확인 없이 시작한다. */
@Service
public class ExpiryCancelService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCancelService.class);

    private final PreorderLedger ledger;
    private final CancelStarter cancelStarter;
    private final Clock clock;

    public ExpiryCancelService(PreorderLedger ledger, CancelStarter cancelStarter, Clock clock) {
        this.ledger = ledger;
        this.cancelStarter = cancelStarter;
        this.clock = clock;
    }

    @Transactional
    public void expire(String preorderToken) {
        PreorderSnapshot preorder = ledger.lockByToken(preorderToken)
                .orElseThrow(() -> new IllegalArgumentException("예약이 없다: " + preorderToken));
        Instant dueAt = preorder.paymentDueAt();
        if (preorder.status() != PreorderStatus.REGISTERED || clock.instant().isBefore(dueAt)) {
            log.info("만료 대상이 아니라 무시한다 preorderId={} status={}", preorderToken, preorder.status());
            return;
        }
        cancelStarter.start(preorder, EventActor.SYSTEM, null, CancelReason.EXPIRY);
    }
}
