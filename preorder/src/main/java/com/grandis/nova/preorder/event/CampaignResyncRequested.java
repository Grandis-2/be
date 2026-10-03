package com.grandis.nova.preorder.event;

/** 대기열이 회차 일정을 잃어 전체 재발행을 요청했다. 대상이 전체라 봉투의 aggregateId 는 비어 있다. */
record CampaignResyncRequested(String requestedBy, String reason) {
}
