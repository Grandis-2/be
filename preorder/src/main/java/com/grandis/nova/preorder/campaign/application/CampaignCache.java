package com.grandis.nova.preorder.campaign.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 회차 원천 캐시(인스턴스 안). 공개 조회 · 내부 조회 · 잠금 전 판정이 회차 행을 DB 에서 다시 읽지 않게 한다.
 * 같은 상품을 동시에 물으면 한 번만 읽는다. "회차 없음" 은 따로 짧게 둔다 — 무작위 id 가 진짜 회차를 밀어내지 못하게.
 * 비우기는 {@link CampaignCacheInvalidation} 이 회차를 바꾼 트랜잭션의 커밋 뒤 모든 인스턴스에 한다.
 */
@Component
public class CampaignCache {

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final Cache<UUID, CachedCampaign> found;
    private final Cache<UUID, Boolean> missing;
    /** 비울 때마다 오른다. 여럿을 한 번에 읽는 동안 비우기가 있었으면 읽은 값을 두지 않는다 — 비우기 전의 값일 수 있다. */
    private final AtomicLong evictions = new AtomicLong();

    CampaignCache(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
                  CampaignCacheProperties properties) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.found = Caffeine.newBuilder()
                .expireAfterWrite(properties.ttl())
                .maximumSize(properties.maxProducts())
                .build();
        this.missing = Caffeine.newBuilder()
                .expireAfterWrite(properties.missingTtl())
                .maximumSize(properties.missingMaxProducts())
                .build();
    }

    /** 회차가 없으면 비어 있다. 공개 여부는 거르지 않는다 — 쓰는 쪽이 정한다. */
    public Optional<CachedCampaign> find(UUID productId) {
        if (missing.getIfPresent(productId) != null) {
            return Optional.empty();
        }
        long before = evictions.get();
        // 읽는 중에 비우면 Caffeine 이 읽기가 끝나길 기다렸다 지우므로 옛 값이 남지 않는다
        CachedCampaign cached = found.get(productId, this::load);
        if (cached == null) {
            rememberMissing(List.of(productId), before);
        }
        return Optional.ofNullable(cached);
    }

    /** 그 상품들의 회차. 회차가 없는 상품은 빠진다. 캐시에 없는 상품만 한 번에 읽는다. */
    public List<CachedCampaign> findAll(Collection<UUID> productIds) {
        Map<UUID, CachedCampaign> result = new LinkedHashMap<>(found.getAllPresent(productIds));
        List<UUID> toLoad = productIds.stream()
                .filter(id -> !result.containsKey(id) && missing.getIfPresent(id) == null)
                .distinct().toList();
        if (!toLoad.isEmpty()) {
            long before = evictions.get();
            Map<UUID, CachedCampaign> loaded = loadAll(toLoad);
            if (evictions.get() == before) {
                found.putAll(loaded);
            }
            rememberMissing(toLoad.stream().filter(id -> !loaded.containsKey(id)).toList(), before);
            result.putAll(loaded);
        }
        return productIds.stream().distinct().map(result::get).filter(Objects::nonNull).toList();
    }

    /** 이 인스턴스에서만 비운다. 모든 인스턴스는 {@link CampaignCacheInvalidation}. */
    void evict(UUID productId) {
        evictions.incrementAndGet();
        found.invalidate(productId);
        missing.invalidate(productId);
    }

    private void rememberMissing(List<UUID> productIds, long before) {
        if (!productIds.isEmpty() && evictions.get() == before) {
            productIds.forEach(id -> missing.put(id, Boolean.TRUE));
        }
    }

    private CachedCampaign load(UUID productId) {
        return campaigns.findById(productId)
                .map(campaign -> toCached(campaign, batches.findByProductIdOrderByBatchNumber(productId)))
                .orElse(null);
    }

    private Map<UUID, CachedCampaign> loadAll(List<UUID> productIds) {
        List<PreorderCampaign> rows = campaigns.findAllById(productIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ShipmentBatch>> batchesByProduct = batches.findByProductIdInOrderByProductIdAscBatchNumberAsc(
                        rows.stream().map(PreorderCampaign::getProductId).toList()).stream()
                .collect(Collectors.groupingBy(ShipmentBatch::getProductId));
        Map<UUID, CachedCampaign> loaded = new HashMap<>();
        for (PreorderCampaign row : rows) {
            loaded.put(row.getProductId(), toCached(row, batchesByProduct.getOrDefault(row.getProductId(), List.of())));
        }
        return loaded;
    }

    private static CachedCampaign toCached(PreorderCampaign campaign, List<ShipmentBatch> configured) {
        List<ShipmentBatchSnapshot> snapshots = configured.stream().map(ShipmentBatch::toSnapshot).toList();
        return new CachedCampaign(campaign.toSchedule(), campaign.isVisible(), campaign.getProductStatus(), snapshots);
    }
}
