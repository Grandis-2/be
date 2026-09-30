package com.grandis.nova.preorder.deadletter;

/**
 * @param preorderToken 예약 공개 UUID. 예약이 없는 이벤트면 null
 * @param redrivable    지금 되돌릴 수 있는가 — 되돌리기를 기다리고(OPEN · 오래된 REDRIVING), 지금 코드로 읽을 수 있는 받는 종류
 */
record DeadLetterView(DeadLetterEvent event, String preorderToken, boolean redrivable) {
}
