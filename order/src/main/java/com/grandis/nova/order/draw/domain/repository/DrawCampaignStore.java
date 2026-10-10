package com.grandis.nova.order.draw.domain.repository;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;

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

    Optional<DrawCampaign> findByIdempotencyKey(String idempotencyKey);

    /** 최신순(만든 시각 · id 내림차순). page 는 0 부터. */
    OffsetPage<DrawCampaign> findNewestFirst(int page, int size);
}
