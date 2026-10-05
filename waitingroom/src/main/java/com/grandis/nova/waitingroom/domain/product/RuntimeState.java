package com.grandis.nova.waitingroom.domain.product;

/** 모델의 대기열이 지금 실제로 어떤 상태인가. 리더가 줄 길이와 배분으로 정한다. */
public enum RuntimeState {

    /** 줄이 없다. 상한 안이면 줄 없이 통과시킨다. */
    IDLE,

    /** 줄이 서 있다. 신규 유입은 뒤에 선다. */
    QUEUEING,

    /** 이번 틱 몫이 대기자 이상이다(credit >= waiting). 유입이 이어지면 다시 QUEUEING 이 된다. */
    DRAINING,

    /** 마감됐다. 배분 대상에서 빠진다. */
    CLOSED
}
