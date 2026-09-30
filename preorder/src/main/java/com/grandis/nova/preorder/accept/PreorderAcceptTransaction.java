package com.grandis.nova.preorder.accept;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.campaign.IssuedPosition;
import com.grandis.nova.preorder.integration.catalog.OptionSnapshot;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.outbox.OutboxWriter;
import com.grandis.nova.preorder.preorder.NewPreorder;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
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
 * 2. 같은 접수 키 재전송 확인 — 잠금 뒤라서 같은 키의 동시 재전송이 순번을 두 번 쓰지 않는다
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
        Optional<CampaignSchedule> campaign =
                campaignLockWait.record(() -> campaigns.lockForAccept(command.productId()));

        Optional<PreorderSnapshot> existing = preorders.findByIdempotencyKey(
                command.customerId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        OptionSnapshot option = requireOnSale(command, product);
        requireAccepting(campaign);
        IssuedPosition issued = campaigns.issuePosition(command.productId());

        String preorderToken = UUID.randomUUID().toString();
        PreorderSnapshot preorder = ledger.accept(new NewPreorder(preorderToken, command.customerId(),
                command.productId(), command.optionId(), issued.batch().id(), issued.position(), command.admissionTicketId(),
                command.idempotencyKey(), option.productTitle(), option.optionTitle(), option.price(),
                command.internalNote()), command.actor(), command.reason());

        String payload = jsonMapper.writeValueAsString(RegisterRequestPayload.of(preorderToken,
                command.customerId(), command.productId(), option.sku(), externalScope));
        Long jobId = syncJobs.createRegister(preorder.id(), payload);
        outboxWriter.append(new RegisterJobReady(jobId, preorderToken));

        return new AcceptResult(preorder, issued.batch(), false);
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

    private OptionSnapshot requireOnSale(AcceptCommand command, Optional<ProductCatalog> product) {
        ProductCatalog found = product
                .filter(ProductCatalog::isOnPreorderSale)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        return found.snapshot(command.optionId())
                .filter(OptionSnapshot::isOnSale)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_OPTION_NOT_FOUND));
    }

    /** 회차가 없는 사전예약 상품은 아직 열리지 않은 것으로 본다. */
    private void requireAccepting(Optional<CampaignSchedule> campaign) {
        CampaignSchedule found = campaign.orElseThrow(() -> new BusinessException(PreorderErrorCode.SALE_NOT_OPEN));
        Instant now = clock.instant();
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
