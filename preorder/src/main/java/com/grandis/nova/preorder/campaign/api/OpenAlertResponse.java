package com.grandis.nova.preorder.campaign.api;

import java.util.UUID;

/** @param subscribed 지금 이 회원이 이 상품에 오픈 알림을 신청해 두었는가 */
public record OpenAlertResponse(UUID productId, boolean subscribed) {
}
