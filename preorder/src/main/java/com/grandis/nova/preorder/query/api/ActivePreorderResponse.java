package com.grandis.nova.preorder.query.api;

import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.query.application.PreorderDisplayStatus;
import com.grandis.nova.preorder.query.application.PreorderView;

import java.time.Instant;
import java.util.UUID;

/**
 * 진행 중인 예약 한 건. 상품 목록에서 예약 여부 · 단계를 보이는 데 필요한 것만 담는다 — 나머지는 상세에서 본다.
 *
 * @param preorderId    상세로 가는 열쇠
 * @param displayStatus 화면 단계(문구는 이 값으로)
 * @param paymentDueAt  결제 기한. 외부 등록이 확인돼 결제를 기다리는 동안에만 있다(기한이 지나도 만료 처리 전이면 남는다)
 */
record ActivePreorderResponse(
        UUID productId,
        String preorderId,
        PreorderDisplayStatus displayStatus,
        Instant paymentDueAt
) {

    public static ActivePreorderResponse from(PreorderView.Active view) {
        PreorderSnapshot preorder = view.preorder();
        return new ActivePreorderResponse(preorder.productId(), preorder.preorderToken(), view.displayStatus(),
                preorder.paymentDueAt());
    }
}
