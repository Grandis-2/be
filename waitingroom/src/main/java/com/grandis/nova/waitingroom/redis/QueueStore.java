package com.grandis.nova.waitingroom.redis;

import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.domain.queue.GraceRetention;
import com.grandis.nova.waitingroom.domain.queue.PollIntervalPolicy;
import com.grandis.nova.waitingroom.domain.queue.QueueEntry;
import com.grandis.nova.waitingroom.domain.queue.QueueState;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/** 요청 경로가 Redis 를 치는 두 곳 — 줄 서기와 순서 조회. 나머지 판정은 스냅샷으로만 한다. */
@Component
public class QueueStore {

    /** 줄 상한 없음. enqueue.lua 의 약속이다(0 은 전원 거절). */
    public static final long UNLIMITED = -1;
    /** 순서 바닥값 수명. 줄이 빈 동안 시계가 뒤로 가도 이전 순서 아래로 서지 않게 넉넉히 둔다. */
    static final long MAX_SCORE_TTL_SEC = 86_400;

    private final LuaScripts scripts;

    public QueueStore(LuaScripts scripts) {
        this.scripts = scripts;
    }

    /**
     * 줄에 세운다. 이미 입장했으면(입장권이 살아 있는 동안) 다시 세우지 않고 입장과 그 시각을 돌려준다.
     *
     * @param maxLength 줄 상한. 제한 없음은 {@link #UNLIMITED}
     */
    public Mono<QueueStatus> enqueue(String productKey, String customerId, long maxLength, Instant now) {
        return scripts.list(scripts.enqueue, RedisKeys.enqueue(productKey), List.of(customerId,
                        String.valueOf(MAX_SCORE_TTL_SEC), String.valueOf(PollIntervalPolicy.aliveTtl().toSeconds()),
                        String.valueOf(maxLength), String.valueOf(now.getEpochSecond()), String.valueOf(GraceRetention.SECONDS),
                        String.valueOf(AdmissionTicket.TTL_SEC), String.valueOf(AdmissionTicket.WINDOW_SEC)))
                .map(reply -> {
                    long admittedAt = Long.parseLong(LuaScripts.text(reply, 5));
                    if (admittedAt >= 0) {
                        return new QueueStatus(QueueEntry.withoutTotal(QueueState.ADMITTED, 0, QueueEntry.NONE, true, false, false),
                                Instant.ofEpochSecond(admittedAt));
                    }
                    long score = Long.parseLong(LuaScripts.text(reply, 0));
                    if (score < 0) {
                        return new QueueStatus(QueueEntry.rejected(), null);
                    }
                    return new QueueStatus(QueueEntry.withoutTotal(QueueState.WAITING, LuaScripts.number(reply, 3), score,
                            LuaScripts.number(reply, 2) == 1, LuaScripts.number(reply, 1) == 1,
                            LuaScripts.number(reply, 4) == 1), null);
                });
    }

    /** 조회가 곧 생존 신호다. 입장했으면 입장 시각을 함께 돌려줘 입장권을 매번 같게 만든다. 입장권 수명이 지난 입장은 줄에 없다. */
    public Mono<QueueStatus> status(String productKey, String customerId, Instant now) {
        return scripts.list(scripts.status, RedisKeys.status(productKey), List.of(customerId,
                        String.valueOf(PollIntervalPolicy.aliveTtl().toSeconds()), String.valueOf(now.getEpochSecond()),
                        String.valueOf(AdmissionTicket.TTL_SEC), String.valueOf(AdmissionTicket.WINDOW_SEC),
                        String.valueOf(GraceRetention.SECONDS)))
                .map(reply -> {
                    QueueState state = QueueState.valueOf(LuaScripts.text(reply, 0));
                    long rank = LuaScripts.number(reply, 1);
                    long score = Long.parseLong(LuaScripts.text(reply, 2));
                    long total = LuaScripts.number(reply, 3);
                    long admittedAt = Long.parseLong(LuaScripts.text(reply, 4));
                    QueueEntry entry = switch (state) {
                        case NOT_QUEUED -> QueueEntry.notQueued();
                        case ADMITTED -> QueueEntry.withoutTotal(QueueState.ADMITTED, 0, score, true, false, false);
                        case WAITING -> new QueueEntry(QueueState.WAITING, rank, score, true, false, false, total);
                        case REJECTED -> throw new IllegalStateException("조회는 거절 상태를 내지 않는다");
                    };
                    return new QueueStatus(entry, admittedAt < 0 ? null : Instant.ofEpochSecond(admittedAt));
                });
    }

    /** @param admittedAt 입장했을 때만 있다 */
    public record QueueStatus(QueueEntry entry, Instant admittedAt) {
    }
}
