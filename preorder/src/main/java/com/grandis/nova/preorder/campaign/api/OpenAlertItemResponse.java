package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.preorder.campaign.domain.OpenAlertStore.MyOpenAlert;

import java.time.Instant;
import java.util.UUID;

/**
 * 내가 오픈 알림을 신청한 사전예약 하나. D-day 는 화면이 opensAt 으로 센다.
 *
 * @param title 상품명. preorder 가 catalog 이벤트로 받아 둔 사본이라, 받기 전이면 "사전예약 상품" 이다
 */
public record OpenAlertItemResponse(UUID productId, String title, Instant opensAt, Instant closesAt) {

    static final String UNTITLED = "사전예약 상품";

    static OpenAlertItemResponse from(MyOpenAlert alert) {
        return new OpenAlertItemResponse(alert.productId(), alert.title() == null ? UNTITLED : alert.title(),
                alert.opensAt(), alert.closesAt());
    }
}
