package com.grandis.nova.preorder.query;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.grandis.nova.preorder.preorder.Preorder;

/**
 * 예약 상세(openapi PreorderDetail). 목록 항목에 외부 예약 번호와 취소 가능 여부가 더 붙는다.
 * 계약의 JSON 은 한 겹이라 목록 항목을 펼쳐서 내보낸다.
 */
record PreorderDetailResponse(
        @JsonUnwrapped PreorderSummaryResponse summary,
        String externalReference,
        boolean cancelable
) {

    public static PreorderDetailResponse from(PreorderView.Summary view) {
        Preorder preorder = view.preorder();
        return new PreorderDetailResponse(PreorderSummaryResponse.from(view),
                preorder.getExternalReference(), preorder.isCancelable());
    }
}
