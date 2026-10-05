package com.grandis.nova.waitingroom.domain.queue;

import java.util.Objects;

/**
 * 줄에서의 자리 — rank 는 내 앞 인원, score 는 순서 값(마이크로초), total 은 나를 포함한 대기 총원.
 * alreadyQueued 는 이미 서 있던 사람(새로고침), rejoined 는 이탈 뒤 다시 선 사람이다(줄 서기 결과에만 있다).
 */
public record QueueEntry(QueueState state, long rank, long score, boolean alreadyQueued, boolean clockWentBack,
                         boolean rejoined, long total) {

    public static final long UNKNOWN_TOTAL = -1;
    /** 줄에 없다. 0번째와 구분하려면 음수여야 한다. */
    public static final long NONE = -1;

    public QueueEntry {
        Objects.requireNonNull(state, "state 는 필수다");
        // 상태마다 가질 수 있는 값이 다르다. 아무 조합이나 만들어지면 운영이 못 만드는 상태를 시험하게 된다
        boolean valid = switch (state) {
            case NOT_QUEUED, REJECTED -> rank == NONE && score == NONE && total == UNKNOWN_TOTAL;
            // 총원은 나를 포함하므로 앞 인원보다 크다. 아니면 두 값을 다른 기준으로 읽은 것이다
            case WAITING -> rank >= 0 && score >= 0 && (total == UNKNOWN_TOTAL || total > rank);
            case ADMITTED -> rank == 0 && (score >= 0 || score == NONE) && total == UNKNOWN_TOTAL;
        };
        if (!valid) {
            throw new IllegalArgumentException("%s 가 가질 수 없는 값이다: rank=%d score=%d total=%d"
                    .formatted(state, rank, score, total));
        }
        // 던지지 않고 낮춘다 — 보고용 값 하나 때문에 줄 서기 결과를 버리지 않는다
        rejoined = rejoined && state == QueueState.WAITING && !alreadyQueued;
    }

    /** 총원을 안 세는 자리(줄 서기 결과). 왕복을 늘리지 않으려고 세지 않는다. */
    public static QueueEntry withoutTotal(QueueState state, long rank, long score, boolean alreadyQueued,
                                          boolean clockWentBack, boolean rejoined) {
        return new QueueEntry(state, rank, score, alreadyQueued, clockWentBack, rejoined, UNKNOWN_TOTAL);
    }

    public static QueueEntry notQueued() {
        return withoutTotal(QueueState.NOT_QUEUED, NONE, NONE, false, false, false);
    }

    public static QueueEntry rejected() {
        return withoutTotal(QueueState.REJECTED, NONE, NONE, false, false, false);
    }

    /** 내 뒤에 선 사람 수. 모르면 UNKNOWN_TOTAL. */
    public long behind() {
        return total == UNKNOWN_TOTAL ? UNKNOWN_TOTAL : total - rank - 1;
    }

    public boolean accepted() {
        return state != QueueState.REJECTED;
    }

    public boolean admitted() {
        return state == QueueState.ADMITTED;
    }
}
