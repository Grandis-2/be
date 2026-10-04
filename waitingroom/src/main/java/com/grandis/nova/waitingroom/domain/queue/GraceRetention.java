package com.grandis.nova.waitingroom.domain.queue;

/**
 * 입장 표시 · 이탈 기록을 들고 있는 기간(초). 입장권 수명보다 길어야 한다 — 표시가 먼저 사라지면
 * 아직 유효한 입장권을 든 사람이 조회에서 "줄에 없음" 을 받고, 다시 서면 그 사이 온 사람들 뒤로 간다.
 */
public final class GraceRetention {

    public static final long SECONDS = Math.max(300, AdmissionTicket.TTL_SEC * 2);

    private GraceRetention() {
    }
}
