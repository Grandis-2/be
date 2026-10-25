package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.application.CampaignCache;
import com.grandis.nova.preorder.campaign.application.CampaignChange;
import com.grandis.nova.preorder.campaign.application.CampaignChangePublisher;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 회차 · 배송 차수(모듈 공개 API). 값은 스냅샷으로만 내준다.
 * 접수는 다른 쓰기를 마친 뒤 {@link #lockForAccept} → {@link #issuePosition} 순으로 부른다 — 순번을 빈틈 · 중복 없이 발급하는 잠금이다.
 */
@Service
public class Campaigns {

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final CampaignChangePublisher changePublisher;
    private final CampaignCache cache;
    private final EntityManager entityManager;

    Campaigns(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
              CampaignChangePublisher changePublisher, CampaignCache cache,
              EntityManager entityManager) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.changePublisher = changePublisher;
        this.cache = cache;
        this.entityManager = entityManager;
    }

    /** 이 인스턴스의 회차 캐시에서 그 상품을 비운다 — 캐시에서 고른 차수가 외래 키에 막혔을 때 다음 시도가 DB 에서 읽게. */
    public void evictCached(UUID productId) {
        cache.evict(productId);
    }

    /** 회차 캐시의 회차 — 잠그기 전 판정용. 비우기를 놓쳤을 때만 캐시 수명만큼 늦으므로 최종 판정은 {@link #lockForAccept} 로 한다. */
    public Optional<CachedCampaign> findCached(UUID productId) {
        return cache.find(productId);
    }

    /**
     * 잠그지 않고 회차 행에서 일정 · 판매 상태를 읽는다 — 접수가 잠그기 전에 판정한다.
     * 읽은 뒤의 변경은 {@link #lockForAccept} 로 다시 본다. 회차가 없으면 비어 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CampaignForAccept> findForAccept(UUID productId) {
        return campaigns.lookup(productId).map(Campaigns::forAccept);
    }

    /**
     * 회차 행을 잠그고 지금 값으로 다시 읽는다. 잠금은 커밋까지라 접수의 다른 쓰기를 마친 뒤 부른다.
     * 앞서 읽은 엔티티는 잠금 조회로는 값이 바뀌지 않아 refresh 로 잠그며 읽는다. 회차가 없으면 비어 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CampaignForAccept> lockForAccept(UUID productId) {
        return Optional.ofNullable(entityManager.find(PreorderCampaign.class, productId)).map(campaign -> {
            entityManager.refresh(campaign, LockModeType.PESSIMISTIC_WRITE);
            return forAccept(campaign);
        });
    }

    private static CampaignForAccept forAccept(PreorderCampaign campaign) {
        return CampaignForAccept.of(campaign.toSchedule(), campaign.isVisible(), campaign.getProductStatus());
    }

    /**
     * 잠근 회차에서 순번 하나를 발급하고 그 순번의 차수를 정한다. {@link #lockForAccept} 와 같은 트랜잭션에서만 부른다.
     * 차수는 오픈 뒤 캐시에서 고르고, 교체와 겹쳐 옛 차수를 골라도 예약의 차수 외래 키가 막는다.
     *
     * @throws IllegalStateException 잠근 회차가 없거나 순번이 속한 차수가 없다
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

    /** 접수 중이거나 window 안에 열릴 회차의 상품 id. */
    @Transactional(readOnly = true)
    public Set<UUID> findAcceptingProductIds(Instant now, Duration window) {
        return Set.copyOf(campaigns.findAcceptingProductIds(now, now.plus(window), ProductStatus.PAUSED));
    }

    /** 증설을 맞출 회차 — 회원에게 보이고 판매 중지가 아닌, 마감 전이고 오픈이 (since, horizon] 안인 회차. 개수와 상관없이 한 행으로 센다. */
    @Transactional(readOnly = true)
    public ScalingDemand findScalingDemand(Instant now, Instant since, Instant horizon) {
        return campaigns.findScalingDemand(now, since, horizon, ProductStatus.PAUSED);
    }

    /** 그 상품에 사전예약 회차가 있는가. */
    /**
     * 관리자 운영 현황의 상품 범위 — 그 단계의 회차를 단계 순서대로 (afterClosesAt, afterProductId) 다음부터 limit 건.
     * 커서가 없으면(null) 처음부터다. 마감 시각 색인(ix_campaign_closes)을 탄다. 잠그지 않는다.
     */
    @Transactional(readOnly = true)
    public List<CampaignSchedule> findPhasePage(CampaignPhase phase, Instant now, Instant afterClosesAt,
                                                UUID afterProductId, int limit) {
        List<PreorderCampaign> found = switch (phase) {
            case OPEN -> campaigns.findOpenPage(now, afterClosesAt, afterProductId, Limit.of(limit));
            case CLOSED -> campaigns.findClosedPage(now, afterClosesAt, afterProductId, Limit.of(limit));
        };
        return found.stream().map(PreorderCampaign::toSchedule).toList();
    }

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

    /** 상품 id → 회차 사본의 상품 이름. 회차가 없거나 이름을 아직 받지 못한 상품은 빠진다. 트랜잭션 없이 문장 하나다. */
    public Map<UUID, String> findTitles(Collection<UUID> productIds) {
        Map<UUID, String> titles = new HashMap<>();
        if (productIds.isEmpty()) {
            return titles;
        }
        for (PreorderCampaign campaign : campaigns.lookupAll(productIds)) {
            if (campaign.getProductTitle() != null) {
                titles.put(campaign.getProductId(), campaign.getProductTitle());
            }
        }
        return titles;
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
