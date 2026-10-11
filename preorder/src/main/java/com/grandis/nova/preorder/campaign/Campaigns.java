package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.application.CampaignChange;
import com.grandis.nova.preorder.campaign.application.CampaignChangePublisher;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 회차 · 배송 차수(모듈 공개 API). 값은 스냅샷으로만 내준다.
 * 접수는 한 트랜잭션에서 {@link #lockForAccept} → {@link #issuePosition} 순으로 부른다 — 순번을 빈틈 · 중복 없이 발급하는 잠금이다.
 */
@Service
public class Campaigns {

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final CampaignChangePublisher changePublisher;

    Campaigns(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
              CampaignChangePublisher changePublisher) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.changePublisher = changePublisher;
    }

    /** 회차 행을 잠그고 일정을 읽는다. 잠금은 호출한 트랜잭션이 끝날 때까지다. 회차가 없으면 비어 있다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CampaignSchedule> lockForAccept(UUID productId) {
        return campaigns.findForUpdate(productId).map(PreorderCampaign::toSchedule);
    }

    /**
     * 잠근 회차에서 순번 하나를 발급하고 그 순번의 차수를 정한다. {@link #lockForAccept} 와 같은 트랜잭션에서만 부른다.
     *
     * @throws IllegalStateException 잠근 회차가 없거나 순번이 속한 차수가 없다(오픈 전 검사를 지나친 데이터)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedPosition issuePosition(UUID productId) {
        // 잠근 회차는 영속성 컨텍스트에 있어 다시 조회하지 않는다
        PreorderCampaign campaign = campaigns.findById(productId)
                .orElseThrow(() -> new IllegalStateException("잠근 회차가 없다: productId=" + productId));
        long position = campaign.issueQueuePosition();
        ShipmentBatch batch = batches.findCovering(productId, position)
                .orElseThrow(() -> new IllegalStateException(
                        "순번이 속한 배송 차수가 없다: productId=" + productId + ", position=" + position));
        return new IssuedPosition(position, batch.toSnapshot());
    }

    /** 예약에 배정된 차수. 없으면 IllegalStateException(오픈 전 검사를 지나친 데이터). */
    @Transactional(readOnly = true)
    public ShipmentBatchSnapshot getBatch(UUID shipmentBatchId) {
        return batches.getAssigned(shipmentBatchId).toSnapshot();
    }

    /** id → 차수. 없는 id 는 빠진다. */
    @Transactional(readOnly = true)
    public Map<UUID, ShipmentBatchSnapshot> findBatches(Collection<UUID> shipmentBatchIds) {
        return batches.findAllById(shipmentBatchIds).stream()
                .collect(Collectors.toMap(ShipmentBatch::getId, ShipmentBatch::toSnapshot));
    }

    /** 판매 중지: 회차를 지금 시각으로 닫는다. 실제로 닫았을 때만 회차 변경 이벤트를 적는다(없거나 이미 지났으면 그대로). */
    @Transactional
    public void closeNow(UUID productId, Instant now) {
        campaigns.findForUpdate(productId)
                .filter(campaign -> campaign.closeNow(now))
                .ifPresent(campaign -> changePublisher.publish(campaign, CampaignChange.CLOSED));
    }

    /**
     * catalog 의 상품 공개 여부를 회차에 반영한다. 번호가 지금보다 클 때만 쓰고, 값이 바뀌었을 때만 회차 변경 이벤트를 적는다.
     *
     * @return 회차가 있어 반영을 판정했으면 true. 아직 없으면 false — 등록 이벤트보다 먼저 왔으므로 호출한 쪽이 늦춰 다시 받는다
     *         (버리면 늦게 온 등록의 옛 값이 굳는다)
     */
    @Transactional
    public boolean applyVisibility(UUID productId, boolean visible, long visibilityVersion) {
        Optional<PreorderCampaign> found = campaigns.findForUpdate(productId);
        if (found.isEmpty()) {
            return false;
        }
        PreorderCampaign campaign = found.get();
        if (campaign.applyVisibility(visible, visibilityVersion)) {
            changePublisher.publish(campaign, CampaignChange.VISIBILITY);
        }
        return true;
    }
}
