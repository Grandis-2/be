package com.grandis.nova.waitingroom.domain.queue;

/** 사용자에게 보여 줄 대기 시간 구간. "모름" 은 없다 — 모르면 가장 넓은 구간으로 접는다. */
public enum EtaDisplay {

    /** 30초 미만. */
    ALMOST_THERE,

    /** 30초 이상 90초 미만. */
    ABOUT_A_MINUTE,

    /** 90초 이상 450초 미만. */
    ABOUT_FIVE_MINUTES,

    /** 450초(7분 30초) 이상. */
    LONG_WAIT
}
