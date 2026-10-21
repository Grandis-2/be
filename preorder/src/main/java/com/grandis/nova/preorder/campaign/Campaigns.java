package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.application.CampaignCache;
import com.grandis.nova.preorder.campaign.application.CampaignChange;
import com.grandis.nova.preorder.campaign.application.CampaignChangePublisher;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
    private final CampaignCache cache;

    Campaigns(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
              CampaignChangePublisher changePublisher, CampaignCache cache) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.changePublisher = changePublisher;
        this.cache = cache;
    }

    /** 회차 캐시의 회차 — 잠그기 전 판정용. 비우기를 놓쳤을 때만 캐시 수명만큼 늦으므로 최종 판정은 {@link #lockForAccept} 로 한다. */
    public Optional<CachedCampaign> findCached(UUID productId) {
        return cache.find(productId);
    }

    /** 회차 행을 잠그고 일정을 읽는다. 잠금은 호출한 트랜잭션이 끝날 때까지다. 회차가 없으면 비어 있다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CampaignSchedule> lockForAccept(UUID productId) {
        return campaigns.findForUpdate(productId).map(PreorderCampaign::toSchedule);
    }

    /**
     * 잠근 회차에서 순번 하나를 발급하고 그 순번의 차수를 정한다. {@link #lockForAccept} 와 같은 트랜잭션에서만 부른다.
     * 차수는 오픈 뒤에 읽어 둔 원천 캐시에서 고르고, 없으면 DB 에서 읽는다 — 잠금 구간의 왕복을 하나 줄인다.
     * 교체와 겹쳐 옛 차수를 골라도 예약의 차수 외래 키가 INSERT 를 막는다(교체는 차수 행을 지우고 새로 넣는다).
     *
     * @throws IllegalStateException 잠근 회차가 없거나 순번이 속한 차수가 없다(오픈 전 검사를 지나친 데이터)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedPosition issuePosition(UUID productId) {
        // 잠근 회차는 영속성 컨텍스트에 있어 다시 조회하지 않는다
        PreorderCampaign campaign = campaigns.findById(productId)
                .orElseThrow(() -> new IllegalStateException("잠근 회차가 없다: productId=" + productId));
        long position = campaign.issueQueuePosition();
        ShipmentBatchSnapshot batch = cache.batchesLoadedSince(productId, campaign.getOpensAt())
                .flatMap(cached -> cached.stream().filter(candidate -> candidate.covers(position)).findFirst())
                .or(() -> batches.findCovering(productId, position).map(ShipmentBatch::toSnapshot))
                .orElseThrow(() -> new IllegalStateException(
                        "순번이 속한 배송 차수가 없다: productId=" + productId + ", position=" + position));
        return new IssuedPosition(position, batch);
    }

    /** 지금 접수 중인(오픈 뒤 · 마감 전 · 공개 · 판매 중지 아님) 회차 수. */
    @Transactional(readOnly = true)
    public long countAccepting(Instant now) {
        return campaigns.countAccepting(now, ProductStatus.PAUSED);
    }

    /** 그 상품에 사전예약 회차가 있는가. */
    @Transactional(readOnly = true)
    public boolean exists(UUID productId) {
        return campaigns.existsById(productId);
    }

    /** 예약에 배정된 차수. 없으면 IllegalStateException(오픈 전 검사를 지나친 데이터). 트랜잭션 없이 문장 하나다. */
    public ShipmentBatchSnapshot getBatch(UUID shipmentBatchId) {
        return batches.getAssigned(shipmentBatchId).toSnapshot();
    }

    /** id → 차수. 없는 id 는 빠진다. 트랜잭션 없이 문장 하나다. */
    public Map<UUID, ShipmentBatchSnapshot> findBatches(Collection<UUID> shipmentBatchIds) {
        if (shipmentBatchIds.isEmpty()) {
            return Map.of();
        }
        return batches.lookupAll(shipmentBatchIds).stream()
                .collect(Collectors.toMap(ShipmentBatch::getId, ShipmentBatch::toSnapshot));
    }

    /** 오픈한 지 window 가 안 된 회차가 있는가 — 신청이 몰리는 구간이다 */
    @Transactional(readOnly = true)
    public boolean anyOpenedWithin(Instant now, Duration window) {
        return campaigns.existsByOpensAtGreaterThanAndOpensAtLessThanEqual(now.minus(window), now);
    }

    /** 판매 중지: 회차를 지금 시각으로 닫는다. 실제로 닫았을 때만 회차 변경 이벤트를 적는다(없거나 이미 지났으면 그대로). */
    @Transactional
    public void closeNow(UUID productId, Instant now) {
        campaigns.findForUpdate(productId)
                .filter(campaign -> campaign.closeNow(now))
                .ifPresent(campaign -> changePublisher.publish(campaign, CampaignChange.CLOSED));
    }

    /**
     * catalog 의 상품명 · 판매 상태 · 공개 여부를 회차의 사본에 반영한다. 번호가 지금보다 클 때만 쓰고, 공개 여부가 바뀌었을 때만
     * 회차 변경 이벤트를 적는다(대기열은 상품명 · 판매 상태를 보지 않는다).
     *
     * @return 회차가 있어 반영을 판정했으면 true. 아직 없으면 false — 등록 이벤트보다 먼저 왔으므로 호출한 쪽이 늦춰 다시 받는다
     *         (버리면 늦게 온 등록의 옛 값이 굳는다)
     */
    @Transactional
    public boolean applyListing(UUID productId, ProductListing listing) {
        Optional<PreorderCampaign> found = campaigns.findForUpdate(productId);
        if (found.isEmpty()) {
            return false;
        }
        PreorderCampaign campaign = found.get();
        changePublisher.listingApplied(campaign, campaign.applyListing(listing));
        return true;
    }
}
