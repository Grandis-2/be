package com.grandis.nova.preorder.deadletter;

/**
 * 원문을 지금 코드로 읽은 결과({@link DeadLetterBodyParser}). 적재 때 분류와 검색 칸을 채우고,
 * 되돌리기 전에 다시 읽어 되돌려도 되는지 가른다.
 *
 * @param preorderToken payload.preorderId(예약 공개 UUID). 없으면 null
 */
record DeadLetterBody(
        String eventId,
        String eventType,
        String aggregateType,
        Long aggregateId,
        String preorderToken,
        FailureReason failureReason
) {

    /** 되돌려서 달라질 수 있는가 — 읽을 수 있고 받는 종류일 때만. */
    boolean redrivable() {
        return failureReason == FailureReason.PROCESSING_FAILED;
    }
}
