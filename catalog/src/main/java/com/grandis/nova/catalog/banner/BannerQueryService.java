package com.grandis.nova.catalog.banner;

import com.grandis.nova.common.redis.cache.ReadCache;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 고객 메인 배너 — 관리자가 켠 배너 중 연결 대상이 지금 살아 있는 것만, 관리자가 정한 순서로(대상이 끝나면 자동 숨김).
 * 결과는 읽기 캐시에 {@link #TTL} 둔다. 배너를 고치면 서버 캐시는 세대로 바로 비우고(CDN · 브라우저는 응답의 max-age 동안 옛 응답일 수 있다),
 * 대상(상품 · 홍보)이 끝난 것은 TTL 안에 반영된다.
 */
@Service
public class BannerQueryService {

    /** 배너를 고치면 올리는 세대. */
    public static final String BANNERS = "banners";
    /** 대상이 숨김 · 판매 중지 · 기간 끝이 된 배너가 빠지기까지의 상한. */
    public static final Duration TTL = Duration.ofSeconds(10);

    private final BannerRepository banners;
    private final BannerTargets targets;
    private final ReadCache cache;
    private final ReadCache.Spec<List<BannerViews.Shown>> spec;

    public BannerQueryService(BannerRepository banners, BannerTargets targets, ReadCache cache) {
        this.banners = banners;
        this.targets = targets;
        this.cache = cache;
        this.spec = cache.spec("banners-shown", new TypeReference<List<BannerViews.Shown>>() { }, TTL);
    }

    public ReadCache.Entry<List<BannerViews.Shown>> shown() {
        return cache.entry(spec, BANNERS, "main", this::load);
    }

    private List<BannerViews.Shown> load() {
        List<Banner> visible = banners.findAllByOrderByPositionAscIdAsc().stream().filter(Banner::isVisible).toList();
        Set<UUID> live = targets.live(visible);
        return visible.stream().filter(b -> live.contains(b.getTargetId())).map(BannerViews.Shown::of).toList();
    }
}
