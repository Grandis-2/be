package com.grandis.nova.order.draw.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * 관리자 화면의 회차 진행 단계. 시각과 결과 반영 여부로 가른다(저장하지 않는다). 따로 구매하는 단계는 없다 — 응모비를 먼저 결제하고 반영 때
 * 당첨 주문을 만든다.
 */
public enum DrawStage {
    /** 응모 시작 전 */
    SCHEDULED,
    /** 응모 중 */
    ENTRY_OPEN,
    /** 마감 뒤 {@link #STALL_AFTER} 안, 결과 반영 전(결제 확인 · 추첨 요청 · 결과 대기) */
    DRAWING,
    /** 마감 뒤 {@link #STALL_AFTER} 가 지나도 결과 반영 전. 저절로 풀리지 않을 수 있어 사람이 본다 */
    STUCK,
    /** 결과 반영 끝 — 당첨 주문을 만들었다 */
    WINNERS_ANNOUNCED;

    /** 마감 뒤 이만큼 지나도 반영하지 못하면 멈춘 것으로 본다 — 시작 금지로 남은 결제 확인 중이 풀리는 시간(결제창 만료 35분)보다 넉넉하게. 지연 경보와 같은 기준이다. */
    public static final Duration STALL_AFTER = Duration.ofHours(1);

    /** 반영 시각이 있으면 시각과 상관없이 발표다. 마감 + {@link #STALL_AFTER} 이 now 와 같으면 멈춤이다(지연 경보의 회차 고르기와 같은 경계). */
    public static DrawStage of(DrawCampaign campaign, Instant settledAt, Instant now) {
        if (settledAt != null) {
            return WINNERS_ANNOUNCED;
        }
        return switch (campaign.phaseAt(now)) {
            case SCHEDULED -> SCHEDULED;
            case OPEN -> ENTRY_OPEN;
            case CLOSED -> campaign.closesAt().plus(STALL_AFTER).isAfter(now) ? DRAWING : STUCK;
        };
    }
}
