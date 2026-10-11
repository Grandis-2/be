package com.grandis.nova.worker.job;

/** 차례 하나의 결과. preorder 의 시도 원장에 이 이름으로 남는다. */
public enum AttemptResult {
    SUCCESS,
    /** 저장되지 않은 것이 확실한 실패 — 다시 보내도 된다. */
    TRANSIENT_FAILURE,
    /** 외부가 받지 않겠다고 한 요청 — 다시 보내도 같다. */
    REJECTED,
    /** 저장됐는지 알 수 없다 — 다음 차례는 조회부터 한다. */
    UNKNOWN
}
