package com.grandis.nova.order.draw.domain.model;

/** 회차의 단계 — 시각으로 가른다(저장하지 않는다). 응모 시작 전 · 응모 중 · 마감. */
public enum DrawPhase {
    SCHEDULED,
    OPEN,
    CLOSED
}
