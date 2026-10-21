package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * preorder 내부 API — 회차 일정(contracts/preorder-internal.md 2절). 토큰 없이 부른다 — 계약이 토큰을 받지 않고, 비회원 목록에는 실을 토큰도 없다.
 * 그래서 토큰 릴레이가 붙지 않는 그룹({@link Dependencies#PREORDER})에 둔다.
 */
@HttpExchange("/internal/preorders/campaigns")
public interface PreorderCampaignClient {

    /** 2.1 — 마감 전이고 이미 열렸거나 곧(preorder 설정, 10분) 열릴 회차. 오픈이 이른 순, 상한 500. */
    @GetExchange("/open")
    ApiResponse<OpenCampaigns> getOpen();

    /**
     * 2.2 — @param productIds 1~100개. 회차가 없는 상품은 결과에서 빠진다(= 판매 준비 전).
     * @param fresh true 면 preorder 가 자기 회차 캐시를 거치지 않고 DB 에서 읽는다. 기본(false)은 그 캐시로 답해, 비우기 알림을 놓친 preorder
     *              인스턴스가 옛 값을 줄 수 있다(preorder 설정 nova.campaign.cache.ttl, 30초). 이 파라미터를 모르는 preorder 는 무시하고 DB 에서 읽는다
     */
    @GetExchange
    ApiResponse<Campaigns> getCampaigns(@RequestParam("productIds") Collection<UUID> productIds, @RequestParam("fresh") boolean fresh);

    record OpenCampaigns(List<Campaign> items, Instant serverTime) {
    }

    record Campaigns(List<Campaign> items) {
    }

    /** 조기 마감 · 판매 중지 마감 · 다시 열기는 closesAt 을, 오픈 전 판매 중지는 opensAt 까지("닫은 시각 − 1ms") 바꾼다. */
    record Campaign(UUID productId, Instant opensAt, Instant closesAt) {
    }
}
