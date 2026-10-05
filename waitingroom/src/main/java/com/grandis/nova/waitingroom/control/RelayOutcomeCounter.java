package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 이 노드가 preorder 로 전달한 접수와 그중 나쁜 응답의 누적. 하트비트에 실으면 리더가 노드별 차이로 센다.
 * 한 회원은 1초에 한 번만 센다 — 한 사람이 요청을 쏟아 브레이크를 걸거나 막지 못하게.
 */
@Component
public class RelayOutcomeCounter {

    /** 1초에 기억할 회원 수의 상한. 넘으면 그 초의 나머지는 회원을 가리지 않고 센다. */
    private static final int MAX_CUSTOMERS_PER_SECOND = 50_000;

    private long relayed;
    private long bad;
    private long currentSecond = Long.MIN_VALUE;
    private final Set<String> countedThisSecond = new HashSet<>();

    public synchronized void record(long epochSecond, String customerId, boolean badOutcome) {
        if (epochSecond > currentSecond) {
            currentSecond = epochSecond;
            countedThisSecond.clear();
        }
        if (countedThisSecond.size() < MAX_CUSTOMERS_PER_SECOND && !countedThisSecond.add(customerId)) {
            return;
        }
        relayed++;
        if (badOutcome) {
            bad++;
        }
    }

    public synchronized RelayOutcome totals() {
        return new RelayOutcome(relayed, bad);
    }
}
