package com.grandis.nova.preorder.accept;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.PreorderCampaign;
import com.grandis.nova.preorder.campaign.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.ShipmentBatch;
import com.grandis.nova.preorder.campaign.ShipmentBatchRepository;
import com.grandis.nova.preorder.integration.catalog.OptionSnapshot;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.outbox.OutboxMessage.RegisterJobReady;
import com.grandis.nova.preorder.outbox.OutboxWriter;
import com.grandis.nova.preorder.preorder.NewPreorder;
import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderRepository;
import com.grandis.nova.preorder.syncjob.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.PreorderSyncJobRepository;
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

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final PreorderRepository preorders;
    private final PreorderLedger ledger;
    private final PreorderSyncJobRepository syncJobs;
    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String externalScope;
    /** 회차 행 잠금을 얻기까지 기다린 시간. 오픈 순간 접수가 이 한 행에 줄을 서므로 부하 시험의 핵심 지표다. */
    private final Timer campaignLockWait;

    PreorderAcceptTransaction(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
                                     PreorderRepository preorders, PreorderLedger ledger,
                                     PreorderSyncJobRepository syncJobs, OutboxWriter outboxWriter,
                                     JsonMapper jsonMapper, Clock clock,
                                     @Value("${nova.external-mock.scope:preorder}") String externalScope,
                                     MeterRegistry meterRegistry) {
        this.campaigns = campaigns;
        this.batches = batches;
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
        Optional<PreorderCampaign> campaign =
                campaignLockWait.record(() -> campaigns.findForUpdate(command.productId()));

        Optional<Preorder> existing = preorders.findByCustomerIdAndIdempotencyKey(
                command.customerId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        OptionSnapshot option = requireOnSale(command, product);
        PreorderCampaign opened = requireAccepting(campaign);

        long position = opened.issueQueuePosition();
        ShipmentBatch batch = batches.findCovering(command.productId(), position)
                .orElseThrow(() -> new IllegalStateException(
                        "순번이 속한 배송 차수가 없다: productId=" + command.productId() + ", position=" + position));

        String preorderToken = UUID.randomUUID().toString();
        Preorder preorder = ledger.accept(new NewPreorder(preorderToken, command.customerId(), command.productId(),
                command.optionId(), batch.getId(), position, command.admissionTicketId(), command.idempotencyKey(),
                option.productTitle(), option.optionTitle(), option.price()), command.actor(), command.reason());
        if (command.internalNote() != null) {
            preorder.changeInternalNote(command.internalNote());
        }

        String payload = jsonMapper.writeValueAsString(RegisterRequestPayload.of(preorderToken,
                command.customerId(), command.productId(), option.sku(), externalScope));
        PreorderSyncJob job = syncJobs.save(PreorderSyncJob.register(preorder.getId(), payload));
        outboxWriter.append(new RegisterJobReady(job.getId(), preorderToken));

        return new AcceptResult(preorder, batch, false);
    }

    /**
     * 같은 접수 키의 기존 예약. 같은 내용이면 그대로 돌려주고(새 순번 없음), 다르면 어느 필드가 다른지 알려 거절한다.
     * UNIQUE 충돌 뒤 다시 확인할 때도 쓴다.
     */
    @Transactional(readOnly = true)
    public Optional<AcceptResult> findReplay(AcceptCommand command) {
        return preorders.findByCustomerIdAndIdempotencyKey(command.customerId(), command.idempotencyKey())
                .map(existing -> replay(existing, command));
    }

    private AcceptResult replay(Preorder existing, AcceptCommand command) {
        List<String> different = new ArrayList<>();
        if (!existing.getProductId().equals(command.productId())) {
            different.add("productId");
        }
        if (!existing.getOptionId().equals(command.optionId())) {
            different.add("optionId");
        }
        if (!different.isEmpty()) {
            throw new BusinessException(PreorderErrorCode.KEY_PAYLOAD_MISMATCH, Map.of("fields", different));
        }
        return new AcceptResult(existing, batches.getAssigned(existing), true);
    }

    private static OptionSnapshot requireOnSale(AcceptCommand command, Optional<ProductCatalog> product) {
        ProductCatalog found = product
                .filter(ProductCatalog::isOnPreorderSale)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        return found.snapshot(command.optionId())
                .filter(OptionSnapshot::isOnSale)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_OPTION_NOT_FOUND));
    }

    /** 회차가 없는 사전예약 상품은 아직 열리지 않은 것으로 본다. */
    private PreorderCampaign requireAccepting(Optional<PreorderCampaign> campaign) {
        PreorderCampaign found = campaign.orElseThrow(() -> new BusinessException(PreorderErrorCode.SALE_NOT_OPEN));
        Instant now = clock.instant();
        if (now.isBefore(found.getOpensAt())) {
            throw new BusinessException(PreorderErrorCode.SALE_NOT_OPEN,
                    Map.of("reason", "opensAt=" + found.getOpensAt()));
        }
        if (!now.isBefore(found.getClosesAt())) {
            throw new BusinessException(PreorderErrorCode.SALE_CLOSED,
                    Map.of("reason", "closesAt=" + found.getClosesAt()));
        }
        return found;
    }
}
