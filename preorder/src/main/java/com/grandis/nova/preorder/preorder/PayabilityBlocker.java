package com.grandis.nova.preorder.preorder;

/** 지금 결제할 수 없는 까닭. */
public enum PayabilityBlocker {
    NOT_YET_REGISTERED,
    DUE_PASSED,
    CANCELING,
    CANCELED
}
