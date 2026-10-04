package com.grandis.nova.waitingroom.redis;

import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.queue.GraceRetention;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 제어 평면(리더 · 하트비트 · 배분 · 스냅샷 · 청소)이 쓰는 Redis 연산. 요청 경로는 쓰지 않는다. */
@Component
public class ControlStore {

    /** 적용한 회차 기록의 수명. 한 임기가 이보다 길게 이어져도 회차는 늘기만 해 다시 막지 않는다. */
    static final long ROUND_TTL_SEC = 86_400;

    private final LuaScripts scripts;
    private final ReactiveStringRedisTemplate redis;

    public ControlStore(LuaScripts scripts, ReactiveStringRedisTemplate redis) {
        this.scripts = scripts;
        this.redis = redis;
    }

    public Mono<LeaderLease> acquireLeader(String ownerId, long leaseMillis) {
        return scripts.list(scripts.acquireLeader, List.of(RedisKeys.LEADER, RedisKeys.LEADER_GENERATION),
                        List.of(ownerId, String.valueOf(leaseMillis)))
                .map(reply -> new LeaderLease(LuaScripts.number(reply, 0) == 1, LuaScripts.text(reply, 1),
                        LuaScripts.number(reply, 3)));
    }

    public Mono<Boolean> releaseLeader(String ownerId) {
        return scripts.single(scripts.releaseLeader, List.of(RedisKeys.LEADER), List.of(ownerId)).map(deleted -> deleted == 1);
    }

    public Mono<ClusterView> heartbeat(String nodeId, long reapAfterSec, long freshSec, long idlePasses) {
        return scripts.list(scripts.heartbeat, List.of(RedisKeys.GATEWAYS), List.of(nodeId, String.valueOf(reapAfterSec),
                        String.valueOf(freshSec), String.valueOf(idlePasses)))
                .map(reply -> new ClusterView((int) LuaScripts.number(reply, 0), LuaScripts.number(reply, 2)));
    }

    public Mono<Long> leave(String nodeId) {
        return scripts.single(scripts.leave, List.of(RedisKeys.GATEWAYS), List.of(nodeId));
    }

    /** 배분 대상 모델과 그것을 읽은 Redis 시각. */
    public Mono<TimedEntries> readProducts() {
        return scripts.list(scripts.readProducts, List.of(RedisKeys.PRODUCTS), List.of()).map(ControlStore::timed);
    }

    /** @return 일정 번호가 더 커서 썼으면 true. 같거나 옛 번호면 false */
    public Mono<Boolean> applySchedule(String productKey, SalesWindow window, long scheduleVersion) {
        return scripts.single(scripts.applySchedule, List.of(RedisKeys.PRODUCTS), List.of(RedisKeys.requireProductKey(productKey),
                        ProductSchedules.format(window, scheduleVersion), String.valueOf(scheduleVersion)))
                .map(written -> written == 1);
    }

    /** 읽었던 값 그대로일 때만 은퇴 표식(일정 번호를 남긴다)으로 바꾼다. @return 바꿨으면 true */
    public Mono<Boolean> retireSchedule(String productKey, String seenValue, long scheduleVersion, Instant now) {
        return scripts.single(scripts.retireSchedule, List.of(RedisKeys.PRODUCTS), List.of(productKey, seenValue,
                        String.valueOf(scheduleVersion), String.valueOf(now.toEpochMilli())))
                .map(retired -> retired == 1);
    }

    /** 읽었던 값 그대로일 때만 일정 목록에서 지운다. @return 지웠으면 true */
    public Mono<Boolean> dropSchedule(String productKey, String seenValue) {
        return scripts.single(scripts.dropSchedule, List.of(RedisKeys.PRODUCTS), List.of(productKey, seenValue))
                .map(removed -> removed == 1);
    }

    /** 재발행 요청을 선점한다. 표식이 없을 때만 세워, 리더가 바뀌는 순간에도 한 노드만 보낸다. @return 선점했으면 true */
    public Mono<Boolean> claimResync(String token, Duration claimTtl) {
        return redis.opsForValue().setIfAbsent(RedisKeys.RESYNC_REQUESTED, token, claimTtl);
    }

    /** 보내지 못했을 때 내 선점만 푼다 — 다음 회차에 다시 요청하게. */
    public Mono<Boolean> releaseResyncClaim(String token) {
        return scripts.single(scripts.releaseResync, List.of(RedisKeys.RESYNC_REQUESTED), List.of(token))
                .map(released -> released == 1);
    }

    /** 연달아 요청한 횟수를 하나 올려 돌려준다. 하루 동안 요청이 없으면 처음부터 센다. */
    public Mono<Long> nextResyncAttempt() {
        return redis.opsForValue().increment(RedisKeys.RESYNC_ATTEMPTS)
                .flatMap(attempt -> redis.expire(RedisKeys.RESYNC_ATTEMPTS, Duration.ofDays(1)).thenReturn(attempt));
    }

    /** 일정을 알게 됐으니 다음 요청은 처음 간격부터 한다. */
    public Mono<Boolean> clearResyncAttempts() {
        return redis.delete(RedisKeys.RESYNC_ATTEMPTS).map(deleted -> deleted > 0);
    }

    /** 보낸 뒤 선점 표식을 조용한 기간 표식으로 바꾼다. */
    public Mono<Boolean> markResyncRequested(Duration quietPeriod) {
        return redis.opsForValue().set(RedisKeys.RESYNC_REQUESTED, "1", quietPeriod);
    }

    /** 운영값 한 칸을 쓴다. 리더가 다음 회차에 읽는다. @return 쓰기 전 값(감사 로그용) */
    public Mono<Optional<String>> writeSetting(String field, String value) {
        return swapSetting(field, "set", value);
    }

    /** 운영값 한 칸을 지워 기본값으로 돌린다. @return 지운 값(감사 로그용) */
    public Mono<Optional<String>> clearSetting(String field) {
        return swapSetting(field, "clear", "");
    }

    /** 바꾸기와 전 값 읽기를 한 번에 — 동시에 바꿔도 감사 로그의 전 값이 맞다. */
    private Mono<Optional<String>> swapSetting(String field, String mode, String value) {
        return redis.execute(scripts.swapSetting, List.of(RedisKeys.SETTINGS), List.of(field, mode, value))
                .next().map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** 지금 리더의 노드 ID. 없으면 빈 값. */
    public Mono<String> readLeaderOwner() {
        return redis.opsForValue().get(RedisKeys.LEADER)
                .map(value -> value.substring(value.indexOf('|') + 1));
    }

    public Mono<Map<String, String>> readSettings() {
        return redis.<String, String>opsForHash().entries(RedisKeys.SETTINGS)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    public Mono<QueueDepth> depth(String productKey) {
        return scripts.list(scripts.depth, RedisKeys.depth(productKey), List.of())
                .map(reply -> new QueueDepth(LuaScripts.number(reply, 0), Long.parseLong(LuaScripts.text(reply, 1))));
    }

    /**
     * @param writtenMax 이 리더가 이 모델에서 본 커서 최댓값. 사라진 커서를 되살리는 데 쓴다(모르면 -1)
     * @param round      회차(리더의 틱 번호). 같은 임기에서 이미 적용한 회차 이하는 커서를 올리지 않고 applied=false 로 답한다
     */
    public Mono<ApplyResult> apply(String productKey, long admit, long fence, long fenceTtlMillis, long writtenMax,
                                   long round) {
        return scripts.list(scripts.apply, RedisKeys.apply(productKey), List.of(String.valueOf(admit),
                        String.valueOf(fence), String.valueOf(fenceTtlMillis), String.valueOf(writtenMax),
                        String.valueOf(round), String.valueOf(ROUND_TTL_SEC)))
                .map(reply -> {
                    long entered = LuaScripts.number(reply, 1);
                    return entered < 0 ? ApplyResult.FENCED
                            : new ApplyResult(Long.parseLong(LuaScripts.text(reply, 0)), entered,
                            LuaScripts.number(reply, 3) == 1, false);
                });
    }

    /** @return 옛 임기라 막혔으면 false */
    public Mono<Boolean> publishSnapshot(long fence, long fenceTtlMillis, Map<String, String> fields) {
        List<String> args = new ArrayList<>(2 + fields.size() * 2);
        args.add(String.valueOf(fence));
        args.add(String.valueOf(fenceTtlMillis));
        fields.forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        return scripts.list(scripts.publishSnapshot, List.of(RedisKeys.SNAPSHOT, RedisKeys.SNAPSHOT_FENCE), args)
                .map(reply -> LuaScripts.number(reply, 0) >= 0);
    }

    /** 판정 재료와 그것을 읽은 Redis 시각. */
    public Mono<TimedEntries> readSnapshot() {
        return scripts.list(scripts.readSnapshot, List.of(RedisKeys.SNAPSHOT), List.of()).map(ControlStore::timed);
    }

    public Mono<SweepResult> sweep(String productKey, int scanLimit, long nowSec, long retentionSec, int budget,
                                   String cursor, boolean removeFront, long term) {
        return scripts.list(scripts.sweep, RedisKeys.sweep(productKey), List.of(String.valueOf(scanLimit),
                        String.valueOf(nowSec), String.valueOf(retentionSec), String.valueOf(budget), cursor,
                        removeFront ? "1" : "0", String.valueOf(term)))
                .map(reply -> new SweepResult(LuaScripts.number(reply, 0), LuaScripts.text(reply, 3),
                        LuaScripts.number(reply, 4) == 1, LuaScripts.number(reply, 5)));
    }

    /** @return 1 지웠다 · 0 안 지웠다 · -1 옛 임기 · -2 표가 없어 막았다 */
    public Mono<Long> closeQueue(String productKey, long fence, boolean confirm, long fenceTtlMillis, long deletableAtSec) {
        return scripts.single(scripts.closeQueue, RedisKeys.close(productKey), List.of(String.valueOf(fence),
                confirm ? "1" : "0", String.valueOf(fenceTtlMillis), String.valueOf(deletableAtSec),
                String.valueOf(GraceRetention.SECONDS * 2)));
    }

    private static TimedEntries timed(List<Object> reply) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 1; i + 1 < reply.size(); i += 2) {
            entries.put(LuaScripts.text(reply, i), LuaScripts.text(reply, i + 1));
        }
        return new TimedEntries(Long.parseLong(LuaScripts.text(reply, 0)), entries);
    }

    /** @param fence 잡았을 때의 임기(못 잡았으면 0) */
    public record LeaderLease(boolean acquired, String owner, long fence) {
    }

    /** @param idlePassSum 신선한 노드들이 직전 1초에 한산 통과시킨 합 */
    public record ClusterView(int aliveGateways, long idlePassSum) {
    }

    public record TimedEntries(long redisNowMillis, Map<String, String> entries) {
    }

    /** @param cursor 입장 커서(-1 = 아직 없음) */
    public record QueueDepth(long waiting, long cursor) {
    }

    public record ApplyResult(long cursor, long entered, boolean applied, boolean fenced) {

        static final ApplyResult FENCED = new ApplyResult(-1, 0, false, true);
    }

    public record SweepResult(long swept, String nextCursor, boolean fenced, long reaped) {
    }
}
