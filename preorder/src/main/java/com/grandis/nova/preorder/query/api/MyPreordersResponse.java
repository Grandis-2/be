package com.grandis.nova.preorder.query.api;

import com.grandis.nova.common.CursorPage;
import com.grandis.nova.preorder.query.application.PreorderDisplayStatus;

import java.util.List;
import java.util.Map;

/**
 * 내 예약 목록(GET /api/v1/preorders). 페이지 칸(items · nextCursor)은 다른 커서 목록과 같고, 화면 단계별 건수를 더한다.
 *
 * @param nextCursor 다음 페이지를 읽을 커서. 마지막 페이지면 null
 * @param counts     화면 단계별 예약 수 — 목록의 조건(status · displayStatus · productId)과 상관없이 내 예약 전부. 모든 단계가 단계 순서로
 *                   들어 있고, 없는 단계는 0
 */
record MyPreordersResponse(List<PreorderSummaryResponse> items, String nextCursor,
                           Map<PreorderDisplayStatus, Long> counts) {

    static MyPreordersResponse of(CursorPage<PreorderSummaryResponse> page, Map<PreorderDisplayStatus, Long> counts) {
        return new MyPreordersResponse(page.items(), page.nextCursor(), counts);
    }
}
