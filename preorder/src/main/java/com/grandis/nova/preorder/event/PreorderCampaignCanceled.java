package com.grandis.nova.preorder.event;

import java.util.UUID;

/** catalog 가 상품의 사전예약 판매를 중지했다. */
record PreorderCampaignCanceled(UUID productId, String reason) {
}
