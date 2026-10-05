package com.grandis.nova.waitingroom.domain.admission;

/**
 * 진입 판정 결과. 사용자가 받는 응답은 통과 · 줄 · 거절 셋이지만 값을 나눈 것은 대응이 달라서다 —
 * 줄로 보낸 이유가 모델 상한인지 노드 상한인지에 따라 조일 대상이 다르다.
 */
public enum AdmissionDecision {

    /** 상한 안이라 줄 없이 입장권을 받는다. 한산한 모델이 나가는 자리다. */
    PASS_UNDER_CAP,

    /** 판정 재료가 낡았다. 모른다는 것이 줄 선 사람을 추월할 사유가 되지 않는다. */
    ENQUEUE_STALE,

    /** 이미 줄이 있다. 뒤에 선다. */
    ENQUEUE_BACKLOG,

    /** 그 모델이 한산 몫을 다 썼다. */
    ENQUEUE_RATE_PRODUCT,

    /** 이 노드가 초당 감당량을 다 썼다. */
    ENQUEUE_RATE_GLOBAL,

    /** 예산은 남았는데 리미터가 키를 더 못 든다. 조일 것은 키 상한이다. */
    ENQUEUE_KEY_SATURATED,

    /** 오픈 전이다. */
    REJECT_NOT_OPEN,

    /** 마감됐다. */
    REJECT_CLOSED,

    /** 줄이 받아 줄 수 있는 길이를 넘었다(최대 대기 시간을 정했을 때만). */
    REJECT_QUEUE_FULL;

    public boolean isPass() {
        return this == PASS_UNDER_CAP;
    }

    public boolean isEnqueue() {
        return switch (this) {
            case ENQUEUE_STALE, ENQUEUE_BACKLOG, ENQUEUE_RATE_PRODUCT, ENQUEUE_RATE_GLOBAL, ENQUEUE_KEY_SATURATED -> true;
            case PASS_UNDER_CAP, REJECT_NOT_OPEN, REJECT_CLOSED, REJECT_QUEUE_FULL -> false;
        };
    }

    public boolean isReject() {
        return !isPass() && !isEnqueue();
    }
}
