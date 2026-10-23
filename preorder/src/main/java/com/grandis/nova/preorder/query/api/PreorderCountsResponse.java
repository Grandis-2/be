package com.grandis.nova.preorder.query.api;

import com.grandis.nova.preorder.query.application.PreorderDisplayStatus;

import java.util.Map;

/**
 * 내 예약의 화면 단계별 수(GET /api/v1/preorders/counts). 목록 없이 숫자만 — 머리글 · 마이페이지 배지가 부른다.
 *
 * @param counts 모든 단계가 단계 순서로 들어 있고, 없는 단계는 0. 목록 응답의 counts 와 같다
 */
record PreorderCountsResponse(Map<PreorderDisplayStatus, Long> counts) {
}
