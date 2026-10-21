package com.grandis.nova.preorder.campaign;

import java.util.UUID;

/** 회차의 일정 · 공개 여부 · 판매 상태가 바뀌었을 수 있다(프로세스 안 알림). 회차를 바꾼 트랜잭션 안에서 낸다. */
public record CampaignScheduleTouched(UUID productId) {
}
