package com.grandis.nova.waitingroom.control;

import org.springframework.stereotype.Component;

/**
 * 이 노드가 초마다 줄 없이 통과시킨 수. 하트비트가 직전 1초 값을 리더에게 알리고, 리더는 그만큼을
 * 줄 배분에서 뺀다 — 한산 통과와 줄 배분이 같은 초당 예산을 쓰게 한다.
 */
@Component
public class IdlePassCounter {

    private long currentSecond = Long.MIN_VALUE;
    private long current;
    private long previous;

    public synchronized void record(long epochSecond) {
        roll(epochSecond);
        if (epochSecond == currentSecond) {
            current++;
        }
    }

    /** 지금 초 직전 1초의 통과 수. */
    public synchronized long lastSecond(long epochSecond) {
        roll(epochSecond);
        return previous;
    }

    private void roll(long epochSecond) {
        if (epochSecond <= currentSecond) {
            return;
        }
        previous = epochSecond == currentSecond + 1 ? current : 0;
        current = 0;
        currentSecond = epochSecond;
    }
}
