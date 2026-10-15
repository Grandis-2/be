package com.grandis.nova.worker.job;

/** 외부 mock 에 보낼 작업의 종류. 동시 요청 자리를 나눌 때도 쓴다. */
public enum JobType {
    REGISTER,
    CANCEL,
    /** 럭키드로우 응모 전달 · 추첨. preorder 의 작업이 아니라 결과 이벤트에 실리지 않는다. */
    DRAW
}
