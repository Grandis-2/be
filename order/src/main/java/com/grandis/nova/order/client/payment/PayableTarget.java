package com.grandis.nova.order.client.payment;

import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.order.domain.model.Order;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * payment 에 넘기는 결제 대상과 그 저장 금액. 만드는 길은 저장된 대상(주문 · 응모)뿐이다 — 금액(BigDecimal)을 따로 받는 길이 없는 것이
 * 구조적 가드다(D15): 프론트가 보낸 금액과 저장 금액이 한 메서드에 섞여도 사용자 금액을 결제 기대값으로 넘길 수 없다.
 * 대상 종류 이름은 payment 소유라 enum 으로 옮기지 않는다.
 */
public final class PayableTarget {

    static final String ORDER = "ORDER";
    static final String DRAW_ENTRY = "DRAW_ENTRY";

    private final String type;
    private final UUID id;
    private final BigDecimal amount;

    private PayableTarget(String type, UUID id, BigDecimal amount) {
        this.type = type;
        this.id = id;
        this.amount = amount;
    }

    /** 주문의 저장된 총액(orders.total_amount). */
    public static PayableTarget of(Order order) {
        return new PayableTarget(ORDER, order.id(), order.totalAmount().amount());
    }

    /** 응모와 그 회차의 응모비(draw_campaigns.entry_fee — 만든 뒤 바꾸지 않는다). */
    public static PayableTarget of(DrawEntry entry, DrawCampaign campaign) {
        if (!entry.campaignId().equals(campaign.id())) {
            throw new IllegalArgumentException("응모의 회차가 아니다: entryId=" + entry.id() + ", campaignId=" + campaign.id());
        }
        return new PayableTarget(DRAW_ENTRY, entry.id(), campaign.entryFee());
    }

    public String type() {
        return type;
    }

    public UUID id() {
        return id;
    }

    public BigDecimal amount() {
        return amount;
    }

    /** 로그용. 금액은 싣지 않는다(로그에 필요하면 따로 찍는다). */
    @Override
    public String toString() {
        return type + ":" + id;
    }
}
