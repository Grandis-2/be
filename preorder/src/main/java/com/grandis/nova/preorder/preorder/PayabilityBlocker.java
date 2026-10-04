package com.grandis.nova.preorder.preorder;

/** 지금 결제할 수 없는 까닭. */
public enum PayabilityBlocker {
    NOT_YET_REGISTERED,
    DUE_PASSED,
    /** 이미 결제가 확인됐다(예약 확정). */
    ALREADY_RESERVED,
    CANCELING,
    CANCELED
}
