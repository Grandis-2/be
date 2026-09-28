package com.grandis.nova.preorder.event;

/** catalog 가 상품의 사전예약 판매를 중지했다. */
record PreorderCampaignCanceled(Long productId, String reason) {
}
