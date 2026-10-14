package com.grandis.nova.waitingroom.domain.queue;

import java.time.Duration;

/**
 * 조회가 끊긴 대기자를 줄에 남겨 두는 시간(생존 신호 수명)의 범위. 늘린 만큼 떠난 사람이 줄에 남아
 * 대기 인원과 예상 시간이 부풀고, 그 사람 차례에 준 입장 몫은 쓰이지 않는다.
 */
public final class LeaveGrace {

    /** 기본값이자 최솟값. 더 줄이면 성실히 조회하는 사람이 걷힌다. */
    public static final Duration MIN = PollIntervalPolicy.aliveTtl();
    /** 떠난 사람이 줄에 남아 대기 인원과 예상 시간을 부풀리는 시간의 상한. */
    public static final Duration MAX = Duration.ofMinutes(30);

    private LeaveGrace() {
    }

    public static boolean accepts(long seconds) {
        return seconds >= MIN.toSeconds() && seconds <= MAX.toSeconds();
    }
}
