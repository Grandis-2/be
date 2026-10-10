package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.payment.PayableTarget;
import com.grandis.nova.order.client.payment.PaymentAttempt;
import com.grandis.nova.order.client.payment.PaymentConfirmSlots;
import com.grandis.nova.order.client.payment.PaymentConfirmation;
import com.grandis.nova.order.client.payment.PaymentConfirmer;
import com.grandis.nova.order.client.payment.PaymentPreparer;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.draw.domain.model.EntryTransition;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.draw.domain.repository.DrawEntryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

/**
 * 응모비 결제 — 주문 결제(order.pay 의 준비 · 승인)와 같은 순서 · 같은 보장이다. 대상이 응모(DRAW_ENTRY)이고 금액이 회차의 응모비다.
 *
 * 준비: 내 응모(결제 대기) → 응모 기간 확인 → payment 에 거래 생성(트랜잭션 밖).
 * 승인: 내 응모 → 금액 대조 → 동시 상한 → 응모 기간 확인 → 결제창 확인(payment, 시작 금지 + 확보) → [tx] 승인 중 + 결제창 번호
 * → payment 승인(트랜잭션 밖) → [tx] 결과 반영({@link DrawEntryPaymentResults}).
 *
 * - 사용자가 보낸 금액은 대조 대상이다. payment 에는 회차의 응모비가 간다({@link PayableTarget#of(DrawEntry, DrawCampaign)}).
 * - 결제 시작은 응모 기간에만 한다 — 승인 중으로 바꾸기 전과 payment 승인을 부르기 직전에 두 번 본다. 지났으면 "시작 금지" 로 결과만
 *   회수한다(승인 중 재요청도 같다). 시작한 결제는 마감 뒤에 확정돼도 결제 완료로 반영한다 — 돈은 이미 나갔고 환불이 없다. 그래서 추첨은
 *   그 회차의 승인 중 응모가 0건이 된 뒤에 한다(NV-396).
 * - 결제 대기로 되돌리는 것은 payment 가 "이 결제창은 앞으로도 시작될 수 없다" 고 확언할 때(NotStartable)와 거절뿐이다. 답을 받지 못하면
 *   응모를 그대로 두고 오류로 답한다 — 앞선 요청이 같은 결제창을 이미 시작했을 수 있다.
 * - 승인 중으로 바꾸는 결제창은 payment 가 이 응모의 것으로 아는 번호뿐이다(결제창 확인) — 그래서 승인 중인 응모는 payment 의 만료 ·
 *   복구가 반드시 결과 이벤트로 푼다.
 * - 동시 상한은 주문 결제와 나눠 쓴다({@link PaymentConfirmSlots}). 외부 호출 동안 잠금을 쥐지 않도록 트랜잭션은 짧게 연다.
 *
 * 결제 키 · 토큰은 로그 · 예외에 싣지 않는다.
 */
@Service
public class DrawEntryPaymentService {

    private static final Logger log = LoggerFactory.getLogger(DrawEntryPaymentService.class);

    private final DrawEntryService entryService;
    private final DrawCampaignStore campaigns;
    private final DrawEntryStore entries;
    private final PaymentPreparer preparer;
    private final PaymentConfirmer confirmer;
    private final DrawEntryPaymentResults results;
    private final PaymentConfirmSlots slots;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public DrawEntryPaymentService(DrawEntryService entryService, DrawCampaignStore campaigns, DrawEntryStore entries, PaymentPreparer preparer,
                                   PaymentConfirmer confirmer, DrawEntryPaymentResults results, PaymentConfirmSlots slots,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.entryService = entryService;
        this.campaigns = campaigns;
        this.entries = entries;
        this.preparer = preparer;
        this.confirmer = confirmer;
        this.results = results;
        this.slots = slots;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * @param sessionToken 사용자가 보낸 액세스 토큰. payment 에 그대로 전달한다
     * @throws BusinessException DRAW_ENTRY_NOT_FOUND · DRAW_ENTRY_NOT_PAYABLE(결제 대기 아님) · DRAW_NOT_OPEN · DEPENDENCY_UNAVAILABLE
     */
    public PreparedEntryPayment prepare(UUID customerId, String sessionToken, UUID drawId) {
        requireNoTransaction();
        DrawEntry entry = entryService.mine(customerId, drawId);
        if (entry.status() != DrawEntryStatus.AWAITING_PAYMENT) {
            throw new BusinessException(OrderErrorCode.DRAW_ENTRY_NOT_PAYABLE);
        }
        DrawCampaign campaign = campaignOf(entry);
        requireOpen(campaign);
        PaymentAttempt attempt = preparer.openCapture(PayableTarget.of(entry, campaign), sessionToken);
        return new PreparedEntryPayment(attempt.providerOrderId(), campaign.entryFee(), campaign.title());
    }

    /**
     * @param providerOrderId 결제창(토스 orderId). 준비 때 받은 값
     * @param amount          결제창이 돌려준 금액(사용자 입력). 응모비와 대조만 한다
     * @throws BusinessException DRAW_ENTRY_NOT_FOUND · DRAW_ENTRY_NOT_PAYABLE · DRAW_NOT_OPEN · PAYMENT_AMOUNT_MISMATCH ·
     *                           PAYMENT_ATTEMPT_NOT_FOUND · DEPENDENCY_UNAVAILABLE(동시 상한 초과 포함)
     */
    public ConfirmedEntryPayment confirm(UUID customerId, String sessionToken, UUID drawId, String providerOrderId, String paymentKey,
                                         BigDecimal amount) {
        requireNoTransaction();
        DrawEntry entry = entryService.mine(customerId, drawId);
        DrawCampaign campaign = campaignOf(entry);
        if (campaign.entryFee().compareTo(amount) != 0) {
            throw new BusinessException(OrderErrorCode.PAYMENT_AMOUNT_MISMATCH, "결제 금액이 응모비와 다릅니다.");
        }
        if (!slots.tryAcquire()) {
            log.warn("응모비 승인 동시 상한 초과 — 상태를 바꾸지 않고 거절 entryId={}", entry.id());
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            return proceed(entry, campaign, sessionToken, providerOrderId, paymentKey, true);
        } finally {
            slots.release();
        }
    }

    /** @param mayStart 결제 대기면 승인을 시작해도 되는가. 시작이 경합에 지면 다시 읽어 한 번만 더 판정한다 */
    private ConfirmedEntryPayment proceed(DrawEntry entry, DrawCampaign campaign, String sessionToken, String providerOrderId, String paymentKey, boolean mayStart) {
        return switch (entry.status()) {
            case AWAITING_PAYMENT -> {
                if (!mayStart) {
                    throw new BusinessException(OrderErrorCode.DRAW_ENTRY_NOT_PAYABLE);
                }
                yield start(entry, campaign, sessionToken, providerOrderId, paymentKey);
            }
            case AUTHORIZING -> providerOrderId.equals(entry.authorizingProviderOrderId())
                    ? call(entry, campaign, providerOrderId, paymentKey, sessionToken, isOpen(campaign))
                    : ConfirmedEntryPayment.pending();
            case PAID -> ConfirmedEntryPayment.approved();
        };
    }

    private ConfirmedEntryPayment start(DrawEntry entry, DrawCampaign campaign, String sessionToken, String providerOrderId,
                                        String paymentKey) {
        requireOpen(campaign);
        switch (confirmer.check(PayableTarget.of(entry, campaign), providerOrderId, paymentKey, sessionToken)) {
            case PaymentConfirmation.Pending pending -> {
            }
            case PaymentConfirmation.Approved approved -> {
                // 결제 대기인데 이 결제창이 이미 승인됐다 — 상태 머신으로는 갈 수 없는 경우다(승인은 승인 중에만 시작된다 · 버그 · 수동 수정의 신호).
                // 그 대상의 유일한 성공 결제이므로 승인 중을 거쳐 아래 승인 호출이 결제 완료로 반영한다
            }
            case PaymentConfirmation.Declined declined -> {
                // 이미 끝난 결제창(거절 · 만료)이다. 승인 중으로 바꾸지 않고 거절로 답한다 — 결제 준비부터 다시
                return new ConfirmedEntryPayment(ConfirmedEntryPayment.Result.DECLINED, entry.status(), declined.reason());
            }
            case PaymentConfirmation.NotStartable notStartable -> throw notStartable.failure();
            case PaymentConfirmation.Unanswered unanswered -> throw unanswered.failure();
        }
        EntryTransition requested;
        try {
            requested = writeTransaction.execute(status -> entries.requestPayment(entry.id(), providerOrderId, clock.instant()));
        } catch (PessimisticLockingFailureException e) {
            // 행 하나만 바꾼다 — 교착은 없고 잠금 대기 초과만 있다. 시작 전이라 아무것도 바뀌지 않았다
            log.warn("응모비 승인 중 전환 잠금 실패 entryId={}", entry.id(), e);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (!requested.applied()) {
            // 읽은 뒤 다른 요청이 먼저 승인을 시작했거나 결제가 끝났다 — 지금 상태로 다시 판정한다
            return proceed(reload(entry.id()), campaign, sessionToken, providerOrderId, paymentKey, false);
        }
        // 결제창 확인(네트워크)을 지나는 사이 마감됐을 수 있다 — 지금 다시 보고, 지났으면 시작하지 않는다(시작 금지 → 확인 중, payment 의 만료가
        // 결제 대기로 되돌린다). 주문의 "승인 중 재요청 · 기한 지남 → 시작 금지" 와 같은 모양
        return call(entry, campaign, providerOrderId, paymentKey, sessionToken, isOpen(campaign));
    }

    private ConfirmedEntryPayment call(DrawEntry entry, DrawCampaign campaign, String providerOrderId, String paymentKey, String sessionToken,
                                       boolean startAllowed) {
        return switch (confirmer.confirm(PayableTarget.of(entry, campaign), providerOrderId, paymentKey, sessionToken, startAllowed)) {
            case PaymentConfirmation.Approved approved -> new ConfirmedEntryPayment(ConfirmedEntryPayment.Result.APPROVED,
                    results.approved(entry.id(), providerOrderId).status(), null);
            case PaymentConfirmation.Declined declined -> new ConfirmedEntryPayment(ConfirmedEntryPayment.Result.DECLINED,
                    results.declined(entry.id(), providerOrderId, declined.reason()).status(), declined.reason());
            case PaymentConfirmation.Pending pending -> ConfirmedEntryPayment.pending();
            case PaymentConfirmation.NotStartable notStartable -> {
                results.notStarted(entry.id(), providerOrderId);
                throw notStartable.failure();
            }
            case PaymentConfirmation.Unanswered unanswered -> throw unanswered.failure();
        };
    }

    private DrawCampaign campaignOf(DrawEntry entry) {
        return readTransaction.execute(status -> campaigns.findById(entry.campaignId()))
                .orElseThrow(() -> new IllegalStateException("응모의 회차가 없다: entryId=" + entry.id()));
    }

    private boolean isOpen(DrawCampaign campaign) {
        return campaign.phaseAt(clock.instant()) == DrawPhase.OPEN;
    }

    private void requireOpen(DrawCampaign campaign) {
        if (!isOpen(campaign)) {
            throw new BusinessException(OrderErrorCode.DRAW_NOT_OPEN);
        }
    }

    private DrawEntry reload(UUID entryId) {
        return readTransaction.execute(status -> entries.findById(entryId))
                .orElseThrow(() -> new IllegalStateException("응모가 사라졌다: " + entryId));
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("응모비 결제는 트랜잭션 밖에서 불러야 한다 — 외부 호출 동안 잠금을 쥐지 않게");
        }
    }
}
