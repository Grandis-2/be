package com.grandis.nova.preorder.deadletter.application;

import com.grandis.nova.preorder.deadletter.domain.DeadLetterSummary;

/**
 * @param preorderToken 예약 공개 UUID. 예약이 없는 이벤트면 null
 * @param redrivable    되돌리기를 기다리고, 저장된 분류 · 종류로 보아 되돌릴 수 있다(원문은 다시 읽지 않는다)
 */
public record DeadLetterListItem(DeadLetterSummary summary, String preorderToken, boolean redrivable) {
}
