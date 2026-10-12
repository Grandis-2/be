package com.grandis.nova.worker.job;

/** 작업을 시작시킨 쪽. 관리자 재처리로 온 작업은 ADMIN 이다. */
public enum Actor {
    SYSTEM,
    ADMIN
}
