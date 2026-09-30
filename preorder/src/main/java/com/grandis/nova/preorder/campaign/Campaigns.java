package com.grandis.nova.preorder.campaign;

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
import java.util.stream.Collectors;

/**
 * 회차 · 배송 차수(모듈 공개 API). 값은 스냅샷으로만 내준다.
 * 접수는 한 트랜잭션에서 {@link #lockForAccept} → {@link #issuePosition} 순으로 부른다 — 순번을 빈틈 · 중복 없이 발급하는 잠금이다.
 */
@Service
public class Campaigns {

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;

    Campaigns(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches) {
        this.campaigns = campaigns;
        this.batches = batches;
    }

    /** 회차 행을 잠그고 일정을 읽는다. 잠금은 호출한 트랜잭션이 끝날 때까지다. 회차가 없으면 비어 있다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CampaignSchedule> lockForAccept(Long productId) {
        return campaigns.findForUpdate(productId).map(CampaignSchedule::of);
    }

    /**
     * 잠근 회차에서 순번 하나를 발급하고 그 순번의 차수를 정한다. {@link #lockForAccept} 와 같은 트랜잭션에서만 부른다.
     *
     * @throws IllegalStateException 잠근 회차가 없거나 순번이 속한 차수가 없다(오픈 전 검사를 지나친 데이터)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedPosition issuePosition(Long productId) {
        // 잠근 회차는 영속성 컨텍스트에 있어 다시 조회하지 않는다
        PreorderCampaign campaign = campaigns.findById(productId)
                .orElseThrow(() -> new IllegalStateException("잠근 회차가 없다: productId=" + productId));
        long position = campaign.issueQueuePosition();
        ShipmentBatch batch = batches.findCovering(productId, position)
                .orElseThrow(() -> new IllegalStateException(
                        "순번이 속한 배송 차수가 없다: productId=" + productId + ", position=" + position));
        return new IssuedPosition(position, ShipmentBatchSnapshot.of(batch));
    }

    /** 예약에 배정된 차수. 없으면 IllegalStateException(오픈 전 검사를 지나친 데이터). */
    @Transactional(readOnly = true)
    public ShipmentBatchSnapshot getBatch(Long shipmentBatchId) {
        return ShipmentBatchSnapshot.of(batches.getAssigned(shipmentBatchId));
    }

    /** id → 차수. 없는 id 는 빠진다. */
    @Transactional(readOnly = true)
    public Map<Long, ShipmentBatchSnapshot> findBatches(Collection<Long> shipmentBatchIds) {
        return batches.findAllById(shipmentBatchIds).stream()
                .collect(Collectors.toMap(ShipmentBatch::getId, ShipmentBatchSnapshot::of));
    }

    /** 판매 중지: 회차를 지금 시각으로 닫는다. 회차가 없으면 아무것도 하지 않는다. */
    @Transactional
    public void closeNow(Long productId, Instant now) {
        campaigns.findForUpdate(productId).ifPresent(campaign -> campaign.closeNow(now));
    }
}
