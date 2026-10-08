package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.application.CampaignChange;
import com.grandis.nova.preorder.campaign.application.CampaignChangePublisher;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchPlan;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import com.grandis.nova.preorder.web.ValidationFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 사전예약 상품 등록에서 회차 · 배송 차수를 처음 만든다(모듈 공개 API). catalog 는 회차 행이 생기는 순간 상품을 노출한다.
 * 이미 회차가 있으면 일정 · 차수는 바꾸지 않는다 — 같은 메시지를 다시 받거나, 관리자가 그사이 고친 값을 덮지 않게.
 * 공개 여부만 번호가 더 클 때 반영한다(관리자가 먼저 만든 회차는 번호 0).
 * 규칙 위반은 예외로 올린다(받은 메시지는 재수신 한도를 넘으면 DLQ 로 간다).
 */
@Service
public class CampaignRegistrar {

    private static final Logger log = LoggerFactory.getLogger(CampaignRegistrar.class);

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final CampaignChangePublisher changePublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    CampaignRegistrar(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
                      CampaignChangePublisher changePublisher, TransactionTemplate transactionTemplate, Clock clock) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.changePublisher = changePublisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 관리자가 동시에 처음 만들면 한쪽이 PK 충돌로 롤백된다. 그 뒤 회차가 있으면 이미 만들어진 것으로 끝낸다.
     *
     * @return 이번에 만들었으면 true, 이미 있어 그대로 두었으면 false
     */
    public boolean register(UUID productId, CampaignRegistration registration) {
        ShipmentBatchPlan plan = toPlan(registration);
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> create(productId, registration, plan)));
        } catch (DataIntegrityViolationException e) {
            if (!campaigns.existsById(productId)) {
                throw e;
            }
            log.info("회차가 동시에 만들어져 등록 이벤트를 건너뛴다 productId={}", productId);
            return false;
        }
    }

    private boolean create(UUID productId, CampaignRegistration registration, ShipmentBatchPlan plan) {
        Optional<PreorderCampaign> existing = campaigns.findForUpdate(productId);
        if (existing.isPresent()) {
            log.info("회차가 이미 있어 등록 이벤트의 일정 · 차수를 건너뛴다 productId={}", productId);
            PreorderCampaign campaign = existing.get();
            if (campaign.applyVisibility(registration.visible(), registration.visibilityVersion())) {
                changePublisher.publish(campaign, CampaignChange.VISIBILITY);
            }
            return false;
        }
        requireSchedule(registration.opensAt(), registration.closesAt());
        PreorderCampaign campaign = campaigns.save(new PreorderCampaign(productId, registration.opensAt(),
                registration.closesAt(), registration.visible(), registration.visibilityVersion()));
        batches.saveAll(plan.toBatches(productId));
        changePublisher.publish(campaign, CampaignChange.CREATED);
        return true;
    }

    /**
     * 오픈은 받는 시각보다 뒤여야 한다. 최소 준비 시간은 catalog 가 등록 요청에서 막는다 —
     * 여기서 다시 보면 릴레이로 늦게 도착한 메시지가 경계에서 DLQ 로 간다.
     */
    private void requireSchedule(Instant opensAt, Instant closesAt) {
        if (!opensAt.isAfter(clock.instant())) {
            throw ValidationFailures.of("opensAt", "현재 시각보다 뒤여야 합니다.");
        }
        if (!closesAt.isAfter(opensAt)) {
            throw ValidationFailures.of("closesAt", "opensAt 보다 뒤여야 합니다.");
        }
    }

    private ShipmentBatchPlan toPlan(CampaignRegistration registration) {
        return new ShipmentBatchPlan(registration.batches().stream()
                .map(batch -> new ShipmentBatchPlan.Line(batch.batchNumber(), batch.positionFrom(), batch.positionTo(),
                        batch.estimatedShipStart(), batch.estimatedShipEnd()))
                .toList());
    }
}
