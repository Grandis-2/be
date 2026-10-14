package com.grandis.nova.order.draw;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.redis.cache.ReadCache;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import jakarta.persistence.PostPersist;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
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
    static final Duration TTL = Duration.ofMinutes(10);

    private final ReadCache cache;
    private final DrawQueryService queries;
    private final ReadCache.Spec<OffsetPage<DrawCampaign>> listSpec;
    private final ReadCache.Spec<DrawCampaign> detailSpec;

    public DrawReadCache(ReadCache cache, DrawQueryService queries) {
        this.cache = cache;
        this.queries = queries;
        this.listSpec = cache.spec("draw-list", new TypeReference<OffsetPage<DrawCampaign>>() { }, TTL);
        this.detailSpec = cache.spec("draw-detail", DrawCampaign.class, TTL);
    }

    public OffsetPage<DrawCampaign> list(int page, int size) {
        return cache.get(listSpec, DRAWS, page + ":" + size, () -> queries.list(page, size));
    }

    /** @throws com.grandis.nova.common.BusinessException 404 DRAW_NOT_FOUND — 없는 회차는 넣지 않는다 */
    public DrawCampaign get(UUID drawId) {
        return cache.get(detailSpec, DRAWS, drawId.toString(), () -> queries.get(drawId));
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
