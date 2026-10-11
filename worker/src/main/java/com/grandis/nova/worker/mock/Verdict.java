package com.grandis.nova.worker.mock;

/** 외부 mock 호출 한 번의 판정. HTTP 상태보다 errorCode 를 먼저 본다. */
public enum Verdict {
    /** 외부에 반영됐다(이미 반영돼 있던 것 포함). */
    SUCCESS,
    /** 저장되지 않은 것이 확실한 실패 — 같은 키로 다시 보내면 된다. */
    TRANSIENT,
    /** 저장됐는지 알 수 없다 — 다음 차례에 같은 키로 조회해 확정한다. */
    UNKNOWN,
    /** 외부가 받지 않겠다고 했다 — 다시 보내도 같다. */
    REJECTED,
    /** mock 이 아닌 곳의 응답이다(주소 · 경로 설정 오류). 시도로 세지 않는다. */
    MISCONFIGURED,
    /** 조회 결과 등록이 없다 — 같은 키로 등록하면 된다. */
    ABSENT,
    /** 호출하지 않았다(차단기가 열림 · 초당 상한). 횟수를 쓰지 않는다. */
    NOT_CALLED
}
