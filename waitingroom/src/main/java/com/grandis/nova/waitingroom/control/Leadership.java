package com.grandis.nova.waitingroom.control;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 이 노드의 리더 여부와 임기(펜스 번호). 리스를 연장 요청 보낸 시각부터 리스 길이(여유를 뺀)까지만 믿는다 —
 * 멈췄다 깨어난 노드가 이미 넘어간 임기로 입장시키지 않게. 노드 ID 는 기동마다 새로 만든다.
 */
@Component
public class Leadership {

    private final String nodeId = UUID.randomUUID().toString();
    private final LongSupplier nanoTime;
    private volatile Term term = Term.NONE;

    public Leadership() {
        this(System::nanoTime);
    }

    Leadership(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public String nodeId() {
        return nodeId;
    }

    /** 지금 믿을 수 있는 임기. 리더가 아니거나 리스가 지났으면 0. */
    public long fence() {
        Term current = term;
        return current.fence() > 0 && nanoTime.getAsLong() - current.validUntilNanos() < 0 ? current.fence() : 0;
    }

    public boolean isLeader() {
        return fence() > 0;
    }

    /**
     * @param requestedAtNanos 연장 요청을 보낸 시각. 응답이 늦어도 그 앞에서 리스가 시작됐다고 본다
     * @return 리더가 새로 되었거나 임기가 바뀌었으면 true
     */
    synchronized boolean hold(long newFence, long requestedAtNanos, Duration trusted) {
        long previous = term.fence();
        term = new Term(newFence, requestedAtNanos + trusted.toNanos());
        return previous != newFence && newFence > 0;
    }

    synchronized void lose() {
        term = Term.NONE;
    }

    /** 그 임기일 때만 내려놓는다. 그사이 새 임기를 받았으면 지우지 않는다. */
    synchronized void lose(long fence) {
        if (term.fence() == fence) {
            term = Term.NONE;
        }
    }

    long nanoTime() {
        return nanoTime.getAsLong();
    }

    private record Term(long fence, long validUntilNanos) {

        static final Term NONE = new Term(0, 0);
    }
}
