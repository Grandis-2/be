package com.grandis.nova.waitingroom.redis;

import com.grandis.nova.waitingroom.domain.queue.QueueEntry;
import com.grandis.nova.waitingroom.domain.queue.QueueState;
import com.grandis.nova.waitingroom.redis.ControlStore.ApplyResult;
import com.grandis.nova.waitingroom.redis.ControlStore.ClusterView;
import com.grandis.nova.waitingroom.redis.ControlStore.LeaderLease;
import com.grandis.nova.waitingroom.support.RedisContainer;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlStoreTest {

    static final String PRODUCT = "101";
    static final Duration WAIT = Duration.ofSeconds(5);
    static final long FENCE_TTL = 60_000;

    private ReactiveStringRedisTemplate redis;
    private ControlStore control;
    private QueueStore queue;
    private Instant now;

    @BeforeEach
    void setUp() {
        redis = RedisContainer.fresh();
        LuaScripts scripts = new LuaScripts(redis);
        control = new ControlStore(scripts, redis);
        queue = new QueueStore(scripts);
        now = Instant.now();
    }

    private void enqueue(String... customers) {
        for (String customer : customers) {
            queue.enqueue(PRODUCT, customer, QueueStore.UNLIMITED, now).block(WAIT);
        }
    }

    @Nested
    class 리더 {

        @Test
        void 한_노드만_잡고_놓으면_다른_노드가_더_큰_임기로_잡는다() {
            LeaderLease a = control.acquireLeader("a", 2_000).block(WAIT);
            LeaderLease b = control.acquireLeader("b", 2_000).block(WAIT);
            LeaderLease renewed = control.acquireLeader("a", 2_000).block(WAIT);

            assertThat(a.acquired()).isTrue();
            assertThat(b.acquired()).isFalse();
            assertThat(b.owner()).isEqualTo("a");
            assertThat(renewed.fence()).as("연장은 같은 임기").isEqualTo(a.fence());

            assertThat(control.releaseLeader("b").block(WAIT)).as("남의 리스는 못 놓는다").isFalse();
            assertThat(control.releaseLeader("a").block(WAIT)).isTrue();
            LeaderLease next = control.acquireLeader("b", 2_000).block(WAIT);
            assertThat(next.acquired()).isTrue();
            assertThat(next.fence()).isGreaterThan(a.fence());
        }

        @Test
        void 임기_카운터가_정수_아닌_표기로_고쳐져도_리더를_뽑고_그_값_아래로_내려가지_않는다() {
            redis.opsForValue().set(RedisKeys.LEADER_GENERATION, "5e15").block(WAIT);

            LeaderLease next = control.acquireLeader("a", 2_000).block(WAIT);
            assertThat(next.acquired()).isTrue();
            assertThat(next.fence()).isEqualTo(5_000_000_000_000_001L);
        }

        @Test
        void 임기_카운터가_상한에_닿으면_낮춰_깔지_않고_선출을_멈춘다() {
            redis.opsForValue().set(RedisKeys.LEADER_GENERATION, "9007199254740991").block(WAIT);

            assertThatThrownBy(() -> control.acquireLeader("a", 2_000).block(WAIT)).rootCause().hasMessageContaining("상한");
            assertThat(redis.opsForValue().get(RedisKeys.LEADER_GENERATION).block(WAIT)).isEqualTo("9007199254740991");
            assertThat(redis.hasKey(RedisKeys.LEADER).block(WAIT)).isFalse();
        }

        @Test
        void 임기_카운터를_잃어도_이전_임기보다_작아지지_않는다() {
            long first = control.acquireLeader("a", 2_000).block(WAIT).fence();
            control.releaseLeader("a").block(WAIT);
            redis.delete(RedisKeys.LEADER_GENERATION).block(WAIT);

            assertThat(control.acquireLeader("b", 2_000).block(WAIT).fence()).isGreaterThan(first);
        }
    }

    @Nested
    class 입장_커서 {

        @Test
        void 몫만큼_올리고_뒤로_가지_않는다() {
            enqueue("a", "b", "c");

            ApplyResult first = control.apply(PRODUCT, 2, 10, FENCE_TTL, -1, 1).block(WAIT);
            ApplyResult none = control.apply(PRODUCT, 0, 10, FENCE_TTL, -1, 2).block(WAIT);

            assertThat(first.entered()).isEqualTo(2);
            assertThat(none.cursor()).isEqualTo(first.cursor());
            assertThat(queue.status(PRODUCT, "b", now).block(WAIT).entry().state()).isEqualTo(QueueState.ADMITTED);
            assertThat(queue.status(PRODUCT, "c", now).block(WAIT).entry().state()).isEqualTo(QueueState.WAITING);
        }

        @Test
        void 줄보다_큰_몫이어도_맨_뒤_사람까지만이고_뒤에_온_사람은_커서_위에_선다() {
            enqueue("a");
            control.apply(PRODUCT, 100, 10, FENCE_TTL, -1, 3).block(WAIT);
            enqueue("late");

            assertThat(queue.status(PRODUCT, "late", now).block(WAIT).entry().state()).isEqualTo(QueueState.WAITING);
        }

        @Test
        void 옛_임기의_리더는_커서를_올리지_못한다() {
            enqueue("a", "b");
            control.apply(PRODUCT, 1, 20, FENCE_TTL, -1, 4).block(WAIT);

            assertThat(control.apply(PRODUCT, 1, 19, FENCE_TTL, -1, 5).block(WAIT).fenced()).isTrue();
            assertThat(control.apply(PRODUCT, 1, 0, FENCE_TTL, -1, 6).block(WAIT).fenced()).as("리더가 아니다").isTrue();
            assertThat(queue.status(PRODUCT, "b", now).block(WAIT).entry().state()).isEqualTo(QueueState.WAITING);
        }

        @Test
        void 같은_임기에서_이미_적용한_회차는_재시도해도_다시_올리지_않고_새_임기는_시계가_뒤여도_막지_않는다() {
            enqueue("a", "b", "c", "d");
            ApplyResult first = control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 500).block(WAIT);

            ApplyResult retried = control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 500).block(WAIT);
            ApplyResult older = control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 499).block(WAIT);
            assertThat(first.applied()).isTrue();
            assertThat(retried.applied()).isFalse();
            assertThat(retried.entered()).isZero();
            assertThat(retried.cursor()).isEqualTo(first.cursor());
            assertThat(older.applied()).as("앞 회차는 거부").isFalse();

            assertThat(control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 501).block(WAIT).entered()).isEqualTo(1);
            assertThat(control.apply(PRODUCT, 1, 11, FENCE_TTL, -1, 400).block(WAIT).entered())
                    .as("새 임기는 Redis 시계가 뒤로 가도 들인다").isEqualTo(1);
            assertThat(queue.status(PRODUCT, "d", now).block(WAIT).entry().state()).isEqualTo(QueueState.WAITING);
        }

        @Test
        void 커서가_사라지면_이_리더가_본_값으로_되살려_들인_사람이_대기로_돌아가지_않는다() {
            enqueue("a", "b");
            ApplyResult applied = control.apply(PRODUCT, 1, 10, FENCE_TTL, -1, 7).block(WAIT);
            redis.delete("wr:admitted:{" + PRODUCT + "}").block(WAIT);

            ApplyResult healed = control.apply(PRODUCT, 0, 10, FENCE_TTL, applied.cursor(), 8).block(WAIT);

            assertThat(healed.cursor()).isEqualTo(applied.cursor());
            assertThat(queue.status(PRODUCT, "a", now).block(WAIT).entry().state()).isEqualTo(QueueState.ADMITTED);
        }
    }

    @Nested
    class 판정_재료 {

        @Test
        void 발행한_필드만_남기고_옛_임기의_발행은_막는다() {
            assertThat(control.publishSnapshot(5, FENCE_TTL, Map.of("p:1", "x", "p:2", "y")).block(WAIT)).isTrue();
            assertThat(control.publishSnapshot(6, FENCE_TTL, Map.of("p:1", "z")).block(WAIT)).isTrue();
            assertThat(control.publishSnapshot(5, FENCE_TTL, Map.of("p:9", "old")).block(WAIT)).isFalse();

            ControlStore.TimedEntries read = control.readSnapshot().block(WAIT);
            assertThat(read.entries()).containsExactly(Map.entry("p:1", "z"));
            assertThat(read.redisNowMillis()).isCloseTo(System.currentTimeMillis(), Offset.offset(5_000L));
        }

        @Test
        void 모델이_많아도_한_번에_발행한다() {
            Map<String, String> fields = IntStream.range(0, 5_000).boxed()
                    .collect(Collectors.toMap(i -> "p:" + i, String::valueOf));

            assertThat(control.publishSnapshot(5, FENCE_TTL, fields).block(WAIT)).isTrue();
            assertThat(control.readSnapshot().block(WAIT).entries()).hasSize(5_000);
        }
    }

    @Nested
    class 하트비트 {

        @Test
        void 살아_있는_노드와_한산_통과_합을_세고_죽은_노드는_지운다() {
            control.heartbeat("a", 5, 2, 3).block(WAIT);
            redis.opsForHash().put(RedisKeys.GATEWAYS, "dead", "1").block(WAIT);
            ClusterView view = control.heartbeat("b", 5, 2, 4).block(WAIT);

            assertThat(view.aliveGateways()).isEqualTo(2);
            assertThat(view.idlePassSum()).isEqualTo(7);
            assertThat(redis.opsForHash().hasKey(RedisKeys.GATEWAYS, "dead").block(WAIT)).isFalse();

            control.leave("a").block(WAIT);
            assertThat(control.heartbeat("b", 5, 2, 0).block(WAIT).aliveGateways()).isEqualTo(1);
        }
    }

    @Nested
    class 청소 {

        @Test
        void 신호가_끊긴_대기자는_이탈로_옮기고_다시_서면_재방문으로_맨_뒤에_선다() {
            enqueue("a", "b");
            long later = now.getEpochSecond() + 1_000;

            ControlStore.SweepResult result = control.sweep(PRODUCT, 100, later, 3_000, 100, "0", true, 1).block(WAIT);
            // 지금(later) 뒤로 살아 있는 신호가 하나도 없으면 아무도 빼지 않는다 — b 의 신호를 살린다
            assertThat(result.swept()).isZero();

            queue.status(PRODUCT, "b", Instant.ofEpochSecond(later)).block(WAIT);
            result = control.sweep(PRODUCT, 100, later, 3_000, 100, "0", true, 1).block(WAIT);
            assertThat(result.swept()).isEqualTo(1);
            assertThat(queue.status(PRODUCT, "a", Instant.ofEpochSecond(later)).block(WAIT).entry().state())
                    .isEqualTo(QueueState.NOT_QUEUED);

            QueueEntry back = queue.enqueue(PRODUCT, "a", QueueStore.UNLIMITED, Instant.ofEpochSecond(later)).block(WAIT).entry();
            assertThat(back.rejoined()).isTrue();
            assertThat(back.rank()).as("떠난 사이 온 사람을 추월하지 않는다").isEqualTo(1);
        }
    }

    @Nested
    class 마감_정리 {

        @Test
        void 표를_세운_다음_회차에_시각이_지났을_때만_줄을_지운다() {
            enqueue("a");
            long past = now.getEpochSecond() - 10;
            long future = now.getEpochSecond() + 3_600;

            assertThat(control.closeQueue(PRODUCT, 7, true, FENCE_TTL, past).block(WAIT)).as("표가 없다").isEqualTo(-2);
            assertThat(control.closeQueue(PRODUCT, 7, true, FENCE_TTL, future).block(WAIT)).as("아직 유예").isZero();
            assertThat(control.closeQueue(PRODUCT, 6, true, FENCE_TTL, past).block(WAIT)).as("옛 임기").isEqualTo(-1);
            assertThat(control.closeQueue(PRODUCT, 7, true, FENCE_TTL, past).block(WAIT)).isEqualTo(1);
            assertThat(redis.hasKey("wr:queue:{" + PRODUCT + "}").block(WAIT)).isFalse();
        }
    }
}
