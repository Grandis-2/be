package com.grandis.nova.preorder.campaign.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final Cache<UUID, Loaded> found;
    private final Cache<UUID, Boolean> missing;
    /** 비울 때마다 오른다. 여럿을 한 번에 읽는 동안 비우기가 있었으면 읽은 값을 두지 않는다 — 비우기 전의 값일 수 있다. */
    private final AtomicLong evictions = new AtomicLong();

    private final Clock clock;

    CampaignCache(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
                  CampaignCacheProperties properties, Clock clock) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.clock = clock;
        this.found = Caffeine.newBuilder()
                .expireAfter(new UntilOpenOrTtl(properties.ttl()))
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
        Loaded cached = found.get(productId, this::load);
        if (cached == null) {
            rememberMissing(List.of(productId), before);
            return Optional.empty();
        }
        return Optional.of(cached.campaign());
    }

    /**
     * 가진 배송 차수 — since 이후에 읽어 둔 것만, 읽지 않는다. 오픈 뒤에 읽었으면 차수는 바뀔 수 없다(오픈 전에만 교체). 오픈 전에 읽은 값은
     * 비우기를 놓쳤을 때 교체 전 차수일 수 있어 주지 않는다.
     */
    public Optional<List<ShipmentBatchSnapshot>> batchesLoadedSince(UUID productId, Instant since) {
        return Optional.ofNullable(found.getIfPresent(productId))
                .filter(cached -> !cached.loadedAt().isBefore(since))
                .map(cached -> cached.campaign().batches());
    }

    /** 그 상품들의 회차. 회차가 없는 상품은 빠진다. 캐시에 없는 상품만 한 번에 읽는다. */
    public List<CachedCampaign> findAll(Collection<UUID> productIds) {
        Map<UUID, CachedCampaign> result = new LinkedHashMap<>();
        found.getAllPresent(productIds).forEach((id, cached) -> result.put(id, cached.campaign()));
        List<UUID> toLoad = productIds.stream()
                .filter(id -> !result.containsKey(id) && missing.getIfPresent(id) == null)
                .distinct().toList();
        if (!toLoad.isEmpty()) {
            long before = evictions.get();
            Instant loadedAt = clock.instant();
            Map<UUID, CachedCampaign> loaded = loadAll(toLoad);
            loaded.forEach((id, campaign) -> putUnlessEvicted(found, id, new Loaded(campaign, loadedAt), before));
            rememberMissing(toLoad.stream().filter(id -> !loaded.containsKey(id)).toList(), before);
            result.putAll(loaded);
        }
        return productIds.stream().distinct().map(result::get).filter(Objects::nonNull).toList();
    }

    /** 이 인스턴스에서만 비운다. 모든 인스턴스는 {@link CampaignCacheInvalidation}. 횟수를 먼저 올린다 — {@link #putUnlessEvicted}. */
    public void evict(UUID productId) {
        evictions.incrementAndGet();
        found.invalidate(productId);
        missing.invalidate(productId);
    }

    private void rememberMissing(List<UUID> productIds, long before) {
        productIds.forEach(id -> putUnlessEvicted(missing, id, Boolean.TRUE, before));
    }

    /**
     * 읽기 시작한 뒤 비우기가 없었을 때만 둔다. 확인과 저장을 그 키의 compute 안에서 한다 — 비우기는 횟수를 올린 뒤 같은 키를 지우므로,
     * 확인이 먼저면 지우기가 뒤따라 지우고, 올리기가 먼저면 확인이 막는다. 그사이 다른 요청이 둔 값은 그대로다.
     */
    private <V> void putUnlessEvicted(Cache<UUID, V> cache, UUID id, V value, long before) {
        cache.asMap().compute(id, (key, current) -> evictions.get() == before ? value : current);
    }

    /** 읽기 시작한 시각을 둔다 — 그 뒤의 커밋만 반영됐다고 볼 수 없으므로 끝난 시각이 아니다. */
    private Loaded load(UUID productId) {
        Instant loadedAt = clock.instant();
        return campaigns.lookup(productId)
                .map(campaign -> new Loaded(toCached(campaign, batches.findByProductIdOrderByBatchNumber(productId)),
                        loadedAt))
                .orElse(null);
    }

    private Map<UUID, CachedCampaign> loadAll(List<UUID> productIds) {
        List<PreorderCampaign> rows = campaigns.lookupAll(productIds);
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

    private record Loaded(CachedCampaign campaign, Instant loadedAt) {
    }

    /**
     * 수명은 ttl, 오픈 전에 읽은 회차는 오픈 시각까지만 — 오픈 순간의 첫 조회가 다시 읽어 그 뒤 접수가 차수를 캐시에서 고른다
     * ({@link #batchesLoadedSince}). 같은 상품의 동시 조회는 한 번만 읽는다.
     */
    private static final class UntilOpenOrTtl implements Expiry<UUID, Loaded> {

        private final long ttlNanos;

        UntilOpenOrTtl(Duration ttl) {
            this.ttlNanos = ttl.toNanos();
        }

        @Override
        public long expireAfterCreate(UUID productId, Loaded loaded, long currentTime) {
            Instant opensAt = loaded.campaign().schedule().opensAt();
            if (!loaded.loadedAt().isBefore(opensAt)) {
                return ttlNanos;
            }
            return Math.min(ttlNanos, Math.max(0, Duration.between(loaded.loadedAt(), opensAt).toNanos()));
        }

        @Override
        public long expireAfterUpdate(UUID productId, Loaded loaded, long currentTime, long currentDuration) {
            return expireAfterCreate(productId, loaded, currentTime);
        }

        @Override
        public long expireAfterRead(UUID productId, Loaded loaded, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    private static CachedCampaign toCached(PreorderCampaign campaign, List<ShipmentBatch> configured) {
        List<ShipmentBatchSnapshot> snapshots = configured.stream().map(ShipmentBatch::toSnapshot).toList();
        return new CachedCampaign(campaign.toSchedule(), campaign.isVisible(), campaign.getProductStatus(), snapshots);
    }
}
