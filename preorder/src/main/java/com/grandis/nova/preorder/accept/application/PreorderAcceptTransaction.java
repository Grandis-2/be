package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.admission.AdmissionTicket;
import com.grandis.nova.preorder.campaign.CampaignForAccept;
import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.IssuedPosition;
import com.grandis.nova.preorder.integration.catalog.OptionSnapshot;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.preorder.AcceptHistory;
import com.grandis.nova.preorder.preorder.NewPreorder;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.syncjob.RegisterJobReady;
import com.grandis.nova.preorder.syncjob.SyncActor;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 접수 트랜잭션. 순서를 바꾸지 않는다(ERD preorders Note).
 *
 * 1. 회차 행 잠금 — 같은 모델의 접수는 여기서 한 줄로 선다
 * 2. 같은 접수 키 재전송 확인 — 잠금 뒤라서 같은 키의 동시 재전송이 순번을 두 번 쓰지 않는다.
 *    마지막 접수 시각(입장권 확인)도 같은 문장에서 읽는다
 * 3. 상품 · 옵션 · 접수 기간 확인
 * 4. 순번 발급 · 차수 배정
 * 5. 예약 + 첫 이력 → REGISTER 작업 → 아웃박스(REGISTER_JOB_READY)
 *
 * 외부 HTTP 는 없다. 상품 정보는 호출하는 쪽이 트랜잭션 밖에서 읽어 넘긴다(카탈로그 캐시).
 * SQS 발행도 없다 — 커밋 뒤 발행기가 아웃박스 알림을 받아 보낸다.
 */
@Component
class PreorderAcceptTransaction {

    private final Campaigns campaigns;
    private final Preorders preorders;
    private final PreorderLedger ledger;
    private final SyncJobs syncJobs;
    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String externalScope;
    /** 회차 행 잠금을 얻기까지 기다린 시간. 오픈 순간 접수가 이 한 행에 줄을 서므로 부하 시험의 핵심 지표다. */
    private final Timer campaignLockWait;

    PreorderAcceptTransaction(Campaigns campaigns,
                                     Preorders preorders, PreorderLedger ledger,
                                     SyncJobs syncJobs, OutboxWriter outboxWriter,
                                     JsonMapper jsonMapper, Clock clock,
                                     @Value("${nova.external-mock.scope:preorder}") String externalScope,
                                     MeterRegistry meterRegistry) {
        this.campaigns = campaigns;
        this.preorders = preorders;
        this.ledger = ledger;
        this.syncJobs = syncJobs;
        this.outboxWriter = outboxWriter;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.externalScope = externalScope;
        this.campaignLockWait = Timer.builder("preorder.campaign.lock.wait")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * @param product 트랜잭션 밖에서 읽은 상품. 없는 상품이면 비어 있다
     */
    @Transactional
    public AcceptResult accept(AcceptCommand command, Optional<ProductCatalog> product) {
        Optional<CampaignForAccept> campaign =
                campaignLockWait.record(() -> campaigns.lockForAccept(command.productId()));

        AcceptHistory history = preorders.findAcceptHistory(command.customerId(), command.productId(),
                command.idempotencyKey());
        if (history.sameKey().isPresent()) {
            return replay(history.sameKey().get(), command);
        }

        requireTicketAfterLastAccept(command, history.lastAcceptedAt());
        OptionSnapshot option = requireOnSale(command, product, campaign);
        requireAccepting(campaign, clock.instant());
        IssuedPosition issued = campaigns.issuePosition(command.productId());

        String preorderToken = UUID.randomUUID().toString();
        PreorderSnapshot preorder = ledger.accept(new NewPreorder(preorderToken, command.customerId(),
                command.productId(), command.optionId(), issued.batch().id(), issued.position(), command.admissionTicketId(),
                command.idempotencyKey(), option.productTitle(), option.optionTitle(), option.price(),
                command.internalNote()), command.actor(), command.reason());

        String payload = jsonMapper.writeValueAsString(RegisterRequestPayload.of(preorderToken,
                command.customerId(), command.productId(), option.sku(), externalScope));
        UUID jobId = syncJobs.createRegister(preorder.id(), payload);
        outboxWriter.append(new RegisterJobReady(jobId, preorderToken, command.productId(), payload,
                SyncActor.SYSTEM, 1));

        return new AcceptResult(preorder, issued.batch(), false);
    }

    /**
     * 잠그기 전 판정 — 캐시가 오픈 전 · 회차 없음이라 한 접수를 잠금 뒤와 같은 우선순위로 거절한다. 트랜잭션 없이 잠그지 않고 읽는다.
     * 그 상품의 예약은 있을 수 없어(일정은 오픈 전에만 바뀐다) 마지막 접수 뒤 입장권 확인은 할 일이 없고, 같은 접수 키 확인만 한다
     * (다른 상품에 쓴 키일 수 있다). 열린 적 있는 회차(마감 포함)는 판정하지 않는다 — 마감 직전 접수가 커밋되기 전에 온 같은 키
     * 재전송은 잠금 뒤에야 그 예약을 본다.
     *
     * @param cached 회차 캐시의 일정 · 판매 상태
     * @return 같은 접수 키의 재전송이면 그 결과. 판정하지 않거나 판정을 다 지나면(캐시가 늦음) 비어 있다 — 호출한 쪽이 잠그고
     *         다시 판정한다
     */
    Optional<AcceptResult> settleBeforeLock(AcceptCommand command, Optional<ProductCatalog> product,
                                            Optional<CampaignForAccept> cached) {
        Instant now = clock.instant();
        if (cached.filter(found -> !now.isBefore(found.schedule().opensAt())).isPresent()) {
            return Optional.empty();
        }
        Optional<AcceptResult> replay = findReplay(command);
        if (replay.isPresent()) {
            return replay;
        }
        requireOnSale(command, product, cached);
        requireAccepting(cached, now);
        return Optional.empty();
    }

    /**
     * 같은 접수 키의 기존 예약. 같은 내용이면 그대로 돌려주고(새 순번 없음), 다르면 어느 필드가 다른지 알려 거절한다.
     * UNIQUE 충돌 뒤 다시 확인할 때도 쓴다.
     */
    @Transactional(readOnly = true)
    public Optional<AcceptResult> findReplay(AcceptCommand command) {
        return preorders.findByIdempotencyKey(command.customerId(), command.idempotencyKey())
                .map(existing -> replay(existing, command));
    }

    private AcceptResult replay(PreorderSnapshot existing, AcceptCommand command) {
        List<String> different = new ArrayList<>();
        if (!existing.productId().equals(command.productId())) {
            different.add("productId");
        }
        if (!existing.optionId().equals(command.optionId())) {
            different.add("optionId");
        }
        if (!different.isEmpty()) {
            throw new BusinessException(PreorderErrorCode.KEY_PAYLOAD_MISMATCH, Map.of("fields", different));
        }
        return new AcceptResult(existing, campaigns.getBatch(existing.shipmentBatchId()), true);
    }

    /** 회차 행의 판매 상태도 옵션보다 먼저 본다 — 판매 중지 · 비공개 상품이 옵션 오류로 존재를 알리지 않게. */
    private OptionSnapshot requireOnSale(AcceptCommand command, Optional<ProductCatalog> product,
                                         Optional<CampaignForAccept> campaign) {
        ProductCatalog found = product
                .filter(ProductCatalog::isOnPreorderSale)
                .filter(ignored -> campaign.filter(CampaignForAccept::onSale).isPresent())
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        return found.snapshot(command.optionId())
                .filter(OptionSnapshot::isOnSale)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_OPTION_NOT_FOUND));
    }

    /**
     * 입장권은 그 회원 · 그 모델의 마지막 접수 뒤에 발급된 것만 받는다 — 같은 입장에서 받은 다른 창의 입장권으로
     * 취소 뒤 줄을 다시 서지 않고 재접수하지 못하게. 회차 잠금 뒤라 동시 접수도 서로의 접수를 본다.
     */
    private static void requireTicketAfterLastAccept(AcceptCommand command, Optional<Instant> lastAcceptedAt) {
        AdmissionTicket ticket = command.admissionTicket();
        if (ticket == null) {
            return;
        }
        lastAcceptedAt
                .filter(acceptedAt -> !acceptedAt.isBefore(ticket.issuedNoEarlierThan()))
                .ifPresent(acceptedAt -> {
                    throw new BusinessException(PreorderErrorCode.ADMISSION_TICKET_STALE);
                });
    }

    /**
     * 회차 행이 없으면 등록이 끝나지 않은 상품이다 — 공개 전 상품처럼 존재를 알리지 않는다. 회차 행 사본이 판매 중지 · 비공개여도
     * 같다(catalog 캐시가 늦어도 거절한다). 일정보다 먼저 봐서 오픈 전이어도 409 로 존재를 알리지 않는다.
     */
    private static void requireAccepting(Optional<CampaignForAccept> campaign, Instant now) {
        CampaignSchedule found = campaign.filter(CampaignForAccept::onSale)
                .map(CampaignForAccept::schedule)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        if (now.isBefore(found.opensAt())) {
            throw new BusinessException(PreorderErrorCode.SALE_NOT_OPEN,
                    Map.of("reason", "opensAt=" + found.opensAt()));
        }
        if (!now.isBefore(found.closesAt())) {
            throw new BusinessException(PreorderErrorCode.SALE_CLOSED,
                    Map.of("reason", "closesAt=" + found.closesAt()));
        }
    }
}
