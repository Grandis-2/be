package com.grandis.nova.order.draw.domain.repository;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.draw.domain.model.DrawCloseState;
import com.grandis.nova.order.draw.domain.model.DrawProgress;
import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 회차 읽기 · 쓰기 포트. */
public interface DrawCampaignStore {

    /**
     * 새 회차. 넣자마자 flush 한다 — 제약 위반이 커밋이 아니라 이 자리에서 드러난다.
     *
     * @throws com.grandis.nova.order.draw.domain.exception.DrawKeyTakenException 같은 Idempotency-Key 의 회차를 다른 요청이 먼저 넣었다
     */
    DrawCampaign insert(NewDrawCampaign campaign);

    Optional<DrawCampaign> findById(UUID id);

    /**
     * 트랜잭션 밖에서 회차 하나를 문장 하나로 읽는다 — {@link #findById} 는 트랜잭션 밖에서 부르면 읽기 전용 트랜잭션을 따로 열어 준비 · 정리 문장 5개가
     * 더 붙는다(실측). 트랜잭션 안의 흐름은 {@link #findById} 를 쓴다 — findById 는 그 트랜잭션이 이미 읽은 회차면 DB 에 가지 않지만 이 쿼리는
     * 늘 왕복하고, 쿼리 전에 쌓인 변경을 내보낼 수도 있다.
     */
    Optional<DrawCampaign> lookup(UUID id);

    /** 없는 id 는 빠진다. 순서는 정하지 않는다. */
    List<DrawCampaign> findAllById(Collection<UUID> ids);

    /** 그 상품을 증정품으로 둔 회차 중 아직 반영(settled_at)되지 않은 것이 있는가 — 마감 뒤 추첨 · 반영 전도 진행 중이다. */
    boolean existsUnsettledByProductId(UUID productId);

    Optional<DrawCampaign> findByIdempotencyKey(String idempotencyKey);

    /** 최신순(만든 시각 · id 내림차순). page 는 0 부터. */
    OffsetPage<DrawCampaign> findNewestFirst(int page, int size);

    /**
     * 그 단계의 회차만, 최신순. 단계는 {@link DrawCampaign#phaseAt} 와 같은 경계로 now 에서 가른다 — 예정 opens_at &gt; now ·
     * 응모 중 opens_at ≤ now &lt; closes_at · 마감 closes_at ≤ now.
     */
    OffsetPage<DrawCampaign> findNewestFirst(DrawPhase phase, Instant now, int page, int size);

    /**
     * 추첨할 차례인 회차 — 마감됐고(closes_at ≤ now) 추첨 요청 · 결과 반영 전이며 결제 확인 중(AUTHORIZING) 응모가 없다. 잠그지 않는다 —
     * 처리할 때 {@link #markDrawRequested} · {@link #markSettled} 의 전제가 다시 가른다.
     */
    List<UUID> findDrawable(Instant now, int limit);

    /** 회차 행을 잠근다(SELECT … FOR UPDATE) — 추첨 요청 · 결과 반영을 회차마다 하나씩 줄 세운다. */
    void lock(UUID campaignId);

    /** 회차 행을 기다리지 않고 잠근다(SELECT … FOR UPDATE SKIP LOCKED). @return 잠갔으면 true, 다른 트랜잭션이 쥐고 있으면 false */
    boolean tryLock(UUID campaignId);

    /** @return 이번에 요청 표식을 적었으면 true(요청 · 반영 전일 때만) */
    boolean markDrawRequested(UUID campaignId, Instant now);

    /** @return 이번에 반영 표식을 적었으면 true(반영 전일 때만) */
    boolean markSettled(UUID campaignId, Instant now);

    Optional<DrawProgress> findProgress(UUID campaignId);

    Optional<DrawCloseState> findCloseState(UUID campaignId);

    /**
     * 회차 표식을 공유 잠금으로 읽는다(SELECT … FOR SHARE) — 그 회차를 잠근 추첨 요청 · 반영이 진행 중이면 커밋을 기다렸다 그 결과를 본다.
     * 트랜잭션 안에서만.
     */
    Optional<DrawCloseState> lockCloseState(UUID campaignId);

    /** closedAfter < closes_at ≤ closedBefore 인데 아직 결과를 반영하지 못한 회차 — 오래 머물면 경보한다. */
    List<UUID> findUnsettled(Instant closedAfter, Instant closedBefore, int limit);
}
