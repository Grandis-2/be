package com.grandis.nova.common.sqs;

/** 받은 메시지의 처리 결과. 실패는 예외로 올린다({@link DeferredRedelivery#handler}). */
public enum MessageHandling {
    /** 처리했다 — 메시지를 지운다. */
    DONE,
    /** 지금은 결과를 정할 수 없다 — 늦춰 다시 받는다. */
    DEFER
}
