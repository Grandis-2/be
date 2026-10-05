package com.grandis.nova.waitingroom.domain.queue;

/** 줄에서의 상태. 없는 것과 맨 앞인 것을 뭉치지 않는다 — 줄에서 빠진 사람에게 "곧 입장" 을 보여 주게 된다. */
public enum QueueState {

    /** 기다리는 중. */
    WAITING,

    /** 차례가 왔다. 줄에서 빠진 상태다. */
    ADMITTED,

    /** 줄에 없다. 아직 안 섰거나 이탈로 지워졌다. */
    NOT_QUEUED,

    /** 줄이 받아 줄 길이를 넘어 못 섰다. */
    REJECTED
}
