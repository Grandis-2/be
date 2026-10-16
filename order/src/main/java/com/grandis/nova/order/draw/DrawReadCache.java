package com.grandis.nova.order.draw;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.redis.cache.ReadCache;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import jakarta.persistence.PostPersist;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 회원 공개 조회(드로우 회차 목록 · 상세)의 읽기 캐시. 관리자 조회 · 응모 · 내 응모는 거치지 않는다.
 * 회차의 응답 칸은 만든 뒤 바뀌지 않는다(수정 API 없음 — 나중에 바뀌는 추첨 요청 · 반영 시각은 응답에 없다). 그래서 TTL 을 길게 두고, 회차를 새로
 * 만들면 커밋 뒤 세대를 올려 목록이 다음 조회부터 새 회차를 싣는다. 단계(예정 · 응모 중 · 마감)는 꺼낸 뒤 지금 시각으로 가른다(DrawResponse).
 * 회차 엔티티의 JPA 콜백({@link PostPersist})에 걸어 만드는 경로가 늘어도 빠지지 않게 한다.
 */
@Component
public class DrawReadCache {

    static final String DRAWS = "draws";
    public static final Duration TTL = Duration.ofMinutes(10);
    static final int CACHED_PAGES = 5;
    /** 회차는 수가 적고 값이 작다 — 캐시마다 인스턴스당 1MB. TTL 이 10분이라 배포 중 겹침도 이 안에서 센다(ReadCache.StoreBudget). */
    static final long BUDGET_BYTES = 1024 * 1024;
    /** 화면이 쓰는 size(목록 100 · 기본 20). */
    static final Set<Integer> CACHED_SIZES = Set.of(20, 100);

    private final ReadCache cache;
    private final DrawQueryService queries;
    private final ReadCache.Spec<OffsetPage<DrawCampaign>> listSpec;
    private final ReadCache.Spec<DrawCampaign> detailSpec;

    public DrawReadCache(ReadCache cache, DrawQueryService queries) {
        this.cache = cache;
        this.queries = queries;
        this.listSpec = cache.spec("draw-list", new TypeReference<OffsetPage<DrawCampaign>>() { }, TTL, BUDGET_BYTES);
        this.detailSpec = cache.spec("draw-detail", DrawCampaign.class, TTL, BUDGET_BYTES);
    }

    /**
     * 앞 페이지({@link #CACHED_PAGES} 미만) · 화면이 쓰는 size({@link #CACHED_SIZES}) · 비지 않은 결과만 Redis 에 넣는다 — 입력을 바꿔 키를
     * 끝없이 만들어 나눠 쓰는 Redis 의 다른 키(토큰 폐기 표식 · 대기열)를 밀어내지 않게. 넣는 양도 메모리 예산이 묶는다(ReadCache).
     *
     * 단계(phase)로 거른 목록은 캐시하지 않는다 — 단계는 시각이 지나면 바뀌는데 캐시는 TTL({@link #TTL}) 동안 그대로라, "응모 중" 목록에 이미
     * 마감된 회차가 최대 TTL 동안 섞인다(응답의 단계 칸은 지금 시각으로 가르므로 한 응답 안에서 필터와 칸이 어긋난다). 거르지 않은 목록은
     * 담기는 회차가 시각과 상관없이 같으므로 그대로 캐시한다. 응답의 Cache-Control(public, max-age 10초)은 거른 목록에도 그대로다 —
     * 브라우저 · CDN 이 든 응답은 최대 10초 낡지만, 한 응답 안의 필터와 단계 칸은 같은 시각이라 어긋나지 않는다.
     *
     * @param phase null 이면 전부
     * @param now   단계를 가르는 시각 — 응답의 단계 칸과 같은 값을 넘긴다
     */
    public ReadCache.Entry<OffsetPage<DrawCampaign>> list(DrawPhase phase, Instant now, int page, int size) {
        if (phase != null) {
            return cache.uncached(() -> queries.list(phase, now, page, size));
        }
        if (page >= CACHED_PAGES || !CACHED_SIZES.contains(size)) {
            return cache.uncached(() -> queries.list(page, size));
        }
        return cache.entry(listSpec, DRAWS, page + ":" + size, () -> queries.list(page, size), found -> !found.items().isEmpty());
    }

    /** @throws com.grandis.nova.common.BusinessException 404 DRAW_NOT_FOUND — 없는 회차는 넣지 않는다 */
    public ReadCache.Entry<DrawCampaign> get(UUID drawId) {
        return cache.entry(detailSpec, DRAWS, drawId.toString(), () -> queries.get(drawId));
    }

    /** 회차 엔티티 리스너 — 새 회차가 커밋되면 세대를 올린다. */
    public static class Invalidation {

        private final ReadCache cache;

        public Invalidation(ReadCache cache) {
            this.cache = cache;
        }

        @PostPersist
        void created(Object entity) {
            cache.bumpAfterCommit(DRAWS);
        }
    }
}
