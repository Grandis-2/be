package com.grandis.nova.preorder.deadletter;

import java.util.Map;

/**
 * 되돌리기를 기다리는 DLQ 메시지의 합계와 종류 · 사유별 수.
 *
 * @param byKind 0 이 아닌 칸만 있다
 */
public record DeadLetterSummary(DeadLetterWaiting total, Map<Kind, Long> byKind) {

    /**
     * @param eventType 받는 종류 이름. 받지 않는 종류는 OTHER, 읽지 못한 메시지는 UNKNOWN — 보낸 쪽 문자열을 그대로 쓰면
     *                  버그 하나로 지표 시계열이 끝없이 는다
     * @param reason    실패 사유 이름
     */
    public record Kind(String eventType, String reason) {
    }
}
