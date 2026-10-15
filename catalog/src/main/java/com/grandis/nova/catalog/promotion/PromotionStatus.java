package com.grandis.nova.catalog.promotion;

/** 홍보 기간으로 가르는 단계(저장하지 않는다). 진행 예정 · 진행 중(끝 날 포함) · 종료. */
public enum PromotionStatus {
    SCHEDULED,
    ONGOING,
    ENDED
}
