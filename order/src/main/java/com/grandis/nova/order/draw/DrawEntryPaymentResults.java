package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.draw.domain.model.EntryTransition;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.draw.domain.repository.DrawEntryStore;
import com.grandis.nova.order.outbox.DrawEntryPaid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 응모비 결제 결과를 응모에 반영한다 — 승인 API 의 동기 응답과 결과 이벤트 소비가 같은 길을 쓴다. 둘 다 오거나 순서가 바뀌어도 전제를 건
 * UPDATE 로 한 번만 반영된다({@link DrawEntryStore}). 결과 하나 = 트랜잭션 하나(행 하나만 바꾼다).
 *
 * - 승인은 결제창을 대조하지 않고 결제 완료로 바꾼다. 결제 대기에 오는 승인은 상태 머신으로 갈 수 없는 경우라 ERROR 로 남기되 받는다 — 그 대상의
 *   유일한 성공 결제이고, 응모에는 취소가 없어 놓치면 돈을 받고도 추첨에서 빠진다.
 * - 결제 완료를 반영한 트랜잭션에서 Mock 전송 이벤트(DRAW_ENTRY_PAID)를 쓴다 — worker 가 받아 Mock(추첨)에 넘긴다.
 * - 거절 · 되돌림은 그 결제창의 승인 중일 때만 결제 대기로 돌린다.
 */
@Service
public class DrawEntryPaymentResults {

    private static final Logger log = LoggerFactory.getLogger(DrawEntryPaymentResults.class);

    private final DrawCampaignStore campaigns;
    private final DrawEntryStore entries;
    private final OutboxWriter outbox;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public DrawEntryPaymentResults(DrawCampaignStore campaigns, DrawEntryStore entries, OutboxWriter outbox,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.campaigns = campaigns;
        this.entries = entries;
        this.outbox = outbox;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    /** 결과 이벤트. 금액이 응모비와 다르면 ERROR 로 남기고 반영은 한다. */
    public EntryTransition settle(EntryPaymentSettlement settlement) {
        DrawEntry entry = readTransaction.execute(status -> entries.findById(settlement.entryId()))
                .orElseThrow(() -> new IllegalArgumentException("응모가 없다: " + settlement.entryId()));
        BigDecimal fee = readTransaction.execute(status -> campaigns.findById(entry.campaignId()))
                .orElseThrow(() -> new IllegalStateException("응모의 회차가 없다: entryId=" + entry.id())).entryFee();
        if (fee.compareTo(settlement.amount()) != 0) {
            log.error("응모비 결제 결과의 금액이 응모비와 다르다 entryId={} providerOrderId={} amount={} fee={}",
                    entry.id(), settlement.providerOrderId(), settlement.amount(), fee);
        }
        return switch (settlement.result()) {
            case APPROVED -> approved(entry.id(), settlement.providerOrderId());
            case DECLINED -> declined(entry.id(), settlement.providerOrderId(), settlement.declineReason());
        };
    }

    EntryTransition approved(UUID entryId, String providerOrderId) {
        Instant now = clock.instant();
        boolean[] fromAwaiting = {false};
        EntryTransition transition = write(entryId, () -> {
            // 먼저 행을 잠그고 갈래를 고른다 — 두 UPDATE 사이에 다른 요청의 승인 중 전환이 끼면 어느 쪽에도 맞지 않아 승인을 놓친다
            DrawEntryStatus before = entries.lockStatus(entryId);
            EntryTransition approved = switch (before) {
                case AUTHORIZING -> entries.approve(entryId, now);
                case AWAITING_PAYMENT -> entries.approveAwaiting(entryId, now);
                case PAID -> new EntryTransition(false, DrawEntryStatus.PAID);
            };
            fromAwaiting[0] = approved.applied() && before == DrawEntryStatus.AWAITING_PAYMENT;
            if (approved.applied()) {
                // 결제 완료와 Mock 전송 이벤트는 한 트랜잭션이다 — 반영이 한 번뿐이라(전제를 건 UPDATE) 이벤트도 한 번 쓴다
                DrawEntry entry = entries.findById(entryId).orElseThrow(() -> new IllegalStateException("응모가 사라졌다: " + entryId));
                outbox.append(new DrawEntryPaid(entry.id(), entry.campaignId(), entry.customerId()));
            }
            return approved;
        });
        if (fromAwaiting[0]) {
            log.error("결제 대기 응모에 승인이 왔다 — 상태 머신으로 갈 수 없는 경우(버그 · 수동 수정 확인), 유일한 성공 결제라 결제 완료로 반영 "
                    + "entryId={} providerOrderId={}", entryId, providerOrderId);
        } else if (transition.applied()) {
            log.info("응모비 결제 완료 entryId={} providerOrderId={}", entryId, providerOrderId);
        }
        return transition;
    }

    EntryTransition declined(UUID entryId, String providerOrderId, DeclineReason reason) {
        EntryTransition transition = revert(entryId, providerOrderId);
        if (transition.applied()) {
            log.info("응모비 결제 거절 — 결제 대기로 entryId={} providerOrderId={} reason={}", entryId, providerOrderId, reason);
        }
        return transition;
    }

    /** payment 가 이 결제창은 앞으로도 시작될 수 없다고 확언했다 — 결제 대기로 되돌린다. */
    EntryTransition notStarted(UUID entryId, String providerOrderId) {
        EntryTransition transition = revert(entryId, providerOrderId);
        if (transition.applied()) {
            log.info("응모비 결제창 시작 불가 — 결제 대기로 entryId={} providerOrderId={}", entryId, providerOrderId);
        }
        return transition;
    }

    /**
     * 행 하나만 바꾸는 트랜잭션이라 교착은 생기지 않는다(바꾸는 칸은 어떤 보조 인덱스에도 없다). 잠금 대기 초과는 주문 결제 반영과 같이 503 이다 —
     * 동기 응답이면 사용자가 다시 보내고, 결과 이벤트면 다시 받는다.
     */
    private EntryTransition write(UUID entryId, Supplier<EntryTransition> change) {
        try {
            return writeTransaction.execute(status -> change.get());
        } catch (PessimisticLockingFailureException e) {
            log.warn("응모비 결제 반영 잠금 실패 entryId={}", entryId, e);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private EntryTransition revert(UUID entryId, String providerOrderId) {
        Instant now = clock.instant();
        // 반영되지 않았으면 다른 결제창의 늦은 결과다(다른 결제창이 승인 중 · 결제 완료) — 되돌리지 않는다
        return write(entryId, () -> entries.revert(entryId, providerOrderId, now));
    }
}
