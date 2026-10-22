package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.redis.ControlStore;
import com.grandis.nova.waitingroom.redis.LuaScripts;
import com.grandis.nova.waitingroom.redis.ProductSchedules;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import com.grandis.nova.waitingroom.support.RedisContainer;
import com.grandis.nova.waitingroom.support.TestJwts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.grandis.nova.waitingroom.support.TestIds.productKey;
import static org.assertj.core.api.Assertions.assertThat;

/** 노드 두 대가 같은 Redis 를 쓸 때 — 리더는 하나, 판정 재료는 둘 다 받고, 리더가 빠지면 넘겨받는다. */
class ControlPlaneTest {

    static final ControlPlaneProperties FAST = new ControlPlaneProperties(Duration.ofMillis(800), Duration.ofMillis(100),
            Duration.ofMillis(100), Duration.ofSeconds(2), null, null, null, null);

    private ReactiveStringRedisTemplate redis;
    private Node first;
    private Node second;

    @BeforeEach
    void setUp() {
        redis = RedisContainer.fresh();
        Instant now = Instant.now();
        redis.opsForHash().put(RedisKeys.PRODUCTS, productKey(101),
                ProductSchedules.format(new SalesWindow(now.minusSeconds(60), now.plusSeconds(3_600)), 1, true)).block();
        first = new Node(redis);
        second = new Node(redis);
    }

    @AfterEach
    void tearDown() {
        first.plane.stop();
        second.plane.stop();
    }

    @Test
    void 리더는_하나이고_두_노드_모두_판정_재료를_받고_분모가_2_다() {
        first.plane.start();
        second.plane.start();

        await(() -> first.holder.current().isPresent() && second.holder.current().isPresent()
                && first.holder.current().get().meta().gatewayCount() == 2
                && first.registry.find("waitingroom.queue.waiting").tag("product", productKey(101)).gauge() != null);

        // 연장이 잠깐 늦으면 순간적으로 리더가 0명일 수 있어, 한 명이 될 때까지 기다린 뒤 둘이 아님을 본다
        await(() -> first.leadership.isLeader() ^ second.leadership.isLeader());
        assertThat(first.leadership.isLeader() && second.leadership.isLeader()).isFalse();
        assertThat(first.holder.stale()).isFalse();
        assertThat(first.holder.current().get().product(productKey(101))).isPresent();
        Node leader = first.leadership.isLeader() ? first : second;
        assertThat(leader.registry.get("waitingroom.leader").gauge().value()).isEqualTo(1.0);
        assertThat(first.registry.get("waitingroom.queue.waiting").tag("product", productKey(101)).gauge().value()).isZero();
    }

    @Test
    void 리더가_멈추면_남은_노드가_이어받아_더_큰_임기로_배분한다() {
        first.plane.start();
        await(() -> first.leadership.isLeader());
        long firstFence = first.leadership.fence();
        second.plane.start();

        first.plane.stop();
        await(() -> second.leadership.isLeader());

        assertThat(second.leadership.fence()).isGreaterThan(firstFence);
        await(() -> second.holder.current().isPresent() && !second.holder.stale());
    }

    @Test
    void 다른_노드가_리스를_쥐고_있으면_연장에서_리더를_내려놓는다() {
        first.leadership.hold(5, first.leadership.nanoTime(), Duration.ofHours(1));
        redis.opsForValue().set(RedisKeys.LEADER, "9|other", Duration.ofSeconds(5)).block();

        first.plane.renewLeadership().block(Duration.ofSeconds(5));

        assertThat(first.leadership.isLeader()).isFalse();
    }

    /** 리더였을 때 센 입장 누적은 늘지 않고 남는다. 지금 리더가 세므로 이 노드 것은 지운다. */
    @Test
    void 리더가_아닌_노드는_틱에서_입장_누적_지표를_지운다() {
        first.metrics.admitted(productKey(101), 3);

        first.plane.tick().block(Duration.ofSeconds(5));

        assertThat(first.registry.find(ControlMetrics.ADMITTED).counters()).isEmpty();
    }

    /** 리더를 잃은 까닭이 Redis 장애면 하트비트도 실패한다. 그동안에도 옛 값을 내보내지 않는다. */
    @Test
    void 하트비트가_실패해도_리더가_아닌_노드는_입장_누적_지표를_지운다() {
        LettuceConnectionFactory unreachable = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        unreachable.afterPropertiesSet();
        unreachable.start();
        try {
            Node down = new Node(new ReactiveStringRedisTemplate(unreachable));
            down.metrics.admitted(productKey(101), 3);

            StepVerifier.create(down.plane.tick()).expectError().verify(Duration.ofSeconds(5));

            assertThat(down.registry.find(ControlMetrics.ADMITTED).counters()).isEmpty();
        } finally {
            unreachable.destroy();
        }
    }

    private static void await(BooleanSupplier condition) {
        StepVerifier.create(Flux.interval(Duration.ofMillis(50)).filter(tick -> condition.getAsBoolean()).next())
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    private static final class Node {

        final Leadership leadership = new Leadership();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final SnapshotHolder holder;
        final ControlMetrics metrics;
        final ControlPlane plane;

        Node(ReactiveStringRedisTemplate redis) {
            ControlStore store = new ControlStore(new LuaScripts(redis), redis);
            RedisClock clock = new RedisClock(Clock.systemUTC());
            holder = new SnapshotHolder(clock, FAST);
            metrics = new ControlMetrics(registry, leadership, holder);
            ScheduleResync resync = new ScheduleResync(store, reason -> Mono.empty(), metrics,
                    new ScheduleResyncProperties(null), Clock.systemUTC());
            plane = new ControlPlane(store, leadership, new AllocationRound(store, new AdmissionProperties(10L, 0.7), AdmissionTicket.of(TestJwts.TOKEN_SECRET, List.of(), null), FAST,
                    metrics, leadership, resync, new Brake(BrakeTest.DEFAULTS, metrics)),
                    holder, clock, new IdlePassCounter(), new RelayOutcomeCounter(), FAST, metrics, new LoopBeats());
        }
    }
}
