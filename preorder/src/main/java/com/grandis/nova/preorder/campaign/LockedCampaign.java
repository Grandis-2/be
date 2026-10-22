package com.grandis.nova.preorder.campaign;

/**
 * 접수가 잠근 회차 — 일정과 회차 행의 판매 상태 사본. catalog 캐시가 비우기를 놓쳐 늦어도 판매 중지 · 비공개는 여기서 거절한다.
 *
 * @param onSale 공개이고 판매 중지가 아니다. 판매 상태를 아직 받지 못했으면(null) catalog 판정에 맡긴다
 */
public record LockedCampaign(CampaignSchedule schedule, boolean onSale) {
}
