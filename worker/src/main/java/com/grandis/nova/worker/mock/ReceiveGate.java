package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.job.JobType;

/**
 * 큐에서 지금 몇 건을 더 받아도 되는가. 받은 메시지는 곧 자리를 잡으러 오므로 자리 수를 넘겨 받지 않고, 호출할 수 없는 동안
 * (차단기가 열림 · 거절이 이어짐)은 받지 않는다 — 메시지가 받은 횟수 · 가시성 시간을 쓰지 않고 큐에서 기다린다.
 */
public class ReceiveGate {

    private final MockCircuit circuit;
    private final ConcurrencyLimiter limiter;
    private final RejectGuard rejectGuard;

    ReceiveGate(MockCircuit circuit, ConcurrencyLimiter limiter, RejectGuard rejectGuard) {
        this.circuit = circuit;
        this.limiter = limiter;
        this.rejectGuard = rejectGuard;
    }

    /** @param inFlight 받아서 아직 처리 중인 건수(모든 큐의 합). 이만큼은 이미 자리를 쓰고 있거나 곧 쓴다 */
    public int receivable(JobType type, int inFlight) {
        if (circuit.isOpen() || rejectGuard.blocked()) {
            return 0;
        }
        int slots = limiter.allowed(type);
        // 반쯤 열린 동안은 시험 호출 수만큼만 연다. 지연이 끝나 다시 보이는 메시지가 시험 요청이 된다
        if (circuit.isHalfOpen()) {
            slots = Math.min(slots, circuit.halfOpenCalls());
        }
        return Math.max(0, slots - inFlight);
    }
}
