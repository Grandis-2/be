package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.redis.ControlStore;
import com.grandis.nova.waitingroom.redis.LuaScripts;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import com.grandis.nova.waitingroom.support.MutableClock;
import com.grandis.nova.waitingroom.support.RedisContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleResyncTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static final Duration PERIODIC = Duration.ofMinutes(10);

    private ReactiveStringRedisTemplate redis;
    private ControlStore control;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-10T01:00:00Z"));
    /** 보낸 요청의 사유. */
    private final List<String> sent = new CopyOnWriteArrayList<>();
    /** 실패까지 포함해 요청을 시도한 횟수. */
    private final AtomicInteger tried = new AtomicInteger();
    private final AtomicBoolean sqsDown = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        redis = RedisContainer.fresh();
        control = new ControlStore(new LuaScripts(redis), redis);
    }

    private ScheduleResync resync(Duration periodic) {
        ControlMetrics metrics = new ControlMetrics(new SimpleMeterRegistry(), new Leadership(), TestSnapshots.emptyHolder());
        return new ScheduleResync(control, reason -> Mono.defer(() -> {
            tried.incrementAndGet();
            return sqsDown.get() ? Mono.error(new IllegalStateException("sqs down")) : Mono.fromRunnable(() -> sent.add(reason));
        }), metrics, new ScheduleResyncProperties(periodic), clock);
    }

    private boolean has(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key).toFuture().join());
    }

    private boolean marked(String key) {
        return "1".equals(redis.opsForValue().get(key).toFuture().join());
    }

    private static void await(BooleanSupplier condition) {
        StepVerifier.create(Flux.interval(Duration.ofMillis(20)).filter(tick -> condition.getAsBoolean()).next())
                .expectNextCount(1).expectComplete().verify(WAIT);
    }

    @Test
    void 일정을_아는_동안에도_주기가_지나면_한_번_요청하고_그_전에는_요청하지_않는다() {
        ScheduleResync resync = resync(PERIODIC);

        resync.requestIfDue();
        await(() -> marked(RedisKeys.RESYNC_PERIODIC));
        assertThat(redis.getExpire(RedisKeys.RESYNC_PERIODIC).block(WAIT)).isLessThanOrEqualTo(PERIODIC);

        clock.advance(PERIODIC.minusSeconds(1));
        resync.requestIfDue();
        assertThat(sent).as("주기 안에는 Redis 에 묻지도 않는다").containsExactly("PERIODIC");

        // 표식의 수명이 다한 것을 대신한다
        redis.delete(RedisKeys.RESYNC_PERIODIC).block(WAIT);
        clock.advance(Duration.ofSeconds(1));
        resync.requestIfDue();
        await(() -> sent.size() == 2);
        assertThat(sent).containsExactly("PERIODIC", "PERIODIC");
    }

    @Test
    void 다른_노드가_세운_주기_표식이_있으면_요청하지_않고_한동안_다시_묻지_않는다() {
        control.markResyncRequested(RedisKeys.RESYNC_PERIODIC, PERIODIC).block(WAIT);
        ScheduleResync resync = resync(PERIODIC);

        resync.requestIfDue();
        // 같은 연결이라 선점을 물은 것이 끝난 뒤에 지워진다
        redis.delete(RedisKeys.RESYNC_PERIODIC).block(WAIT);
        resync.requestIfDue();
        assertThat(has(RedisKeys.RESYNC_PERIODIC)).as("표식을 본 뒤 선점 시한 동안은 다시 묻지 않는다").isFalse();
        assertThat(sent).isEmpty();

        clock.advance(ScheduleResync.CLAIM_TTL);
        resync.requestIfDue();
        await(() -> marked(RedisKeys.RESYNC_PERIODIC));
        assertThat(sent).containsExactly("PERIODIC");
    }

    @Test
    void 주기를_0_으로_끄면_일정을_아는_동안은_요청하지_않는다() {
        ScheduleResync resync = resync(Duration.ZERO);

        resync.requestIfDue();
        resync.requestIfQuiet();
        await(() -> marked(RedisKeys.RESYNC_REQUESTED));

        assertThat(sent).containsExactly("SCHEDULE_EMPTY");
        assertThat(has(RedisKeys.RESYNC_PERIODIC)).isFalse();
    }

    @Test
    void 일정을_몰라_전체를_요청했으면_주기_요청도_그만큼_미룬다() {
        ScheduleResync resync = resync(PERIODIC);

        resync.requestIfQuiet();
        await(() -> marked(RedisKeys.RESYNC_PERIODIC));
        resync.requestIfDue();

        assertThat(sent).containsExactly("SCHEDULE_EMPTY");
    }

    @Test
    void 연달아_보내지_못하면_쉬는_기간을_늘리고_다시_되면_처음으로_돌린다() {
        ScheduleResync resync = resync(PERIODIC);
        sqsDown.set(true);

        resync.requestIfQuiet();
        await(() -> tried.get() == 1 && !has(RedisKeys.RESYNC_REQUESTED));
        clock.advance(ScheduleResync.FIRST_RETRY.minusSeconds(1));
        resync.requestIfQuiet();
        assertThat(tried).as("쉬는 동안은 요청하지 않는다").hasValue(1);

        clock.advance(Duration.ofSeconds(1));
        resync.requestIfQuiet();
        await(() -> tried.get() == 2 && !has(RedisKeys.RESYNC_REQUESTED));
        clock.advance(ScheduleResync.FIRST_RETRY);
        resync.requestIfQuiet();
        assertThat(tried).as("두 번째 실패 뒤에는 두 배를 쉰다").hasValue(2);

        sqsDown.set(false);
        clock.advance(ScheduleResync.FIRST_RETRY);
        resync.requestIfQuiet();
        await(() -> marked(RedisKeys.RESYNC_REQUESTED));
        assertThat(sent).containsExactly("SCHEDULE_EMPTY");

        // 회복한 뒤의 실패는 처음 간격부터 쉰다
        sqsDown.set(true);
        redis.delete(RedisKeys.RESYNC_REQUESTED).block(WAIT);
        resync.requestIfQuiet();
        await(() -> tried.get() == 4 && !has(RedisKeys.RESYNC_REQUESTED));
        sqsDown.set(false);
        clock.advance(ScheduleResync.FIRST_RETRY);
        resync.requestIfQuiet();
        await(() -> sent.size() == 2);
    }

    @Test
    void 보내지_못한_뒤_쉬는_기간은_5초에서_두_배씩_1분까지다() {
        assertThat(ScheduleResync.retryAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(ScheduleResync.retryAfter(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(ScheduleResync.retryAfter(4)).isEqualTo(Duration.ofSeconds(40));
        assertThat(ScheduleResync.retryAfter(5)).isEqualTo(ScheduleResync.MAX_RETRY);
        assertThat(ScheduleResync.retryAfter(100)).isEqualTo(ScheduleResync.MAX_RETRY);
    }

    @Test
    void 주기는_기본_10분이고_0_이_아니면서_선점_시한보다_짧거나_음수면_기동을_막는다() {
        assertThat(new ScheduleResyncProperties(null).periodic()).isEqualTo(Duration.ofMinutes(10));
        assertThat(new ScheduleResyncProperties(Duration.ZERO).periodicEnabled()).isFalse();
        assertThat(new ScheduleResyncProperties(ScheduleResync.CLAIM_TTL).periodicEnabled()).isTrue();
        assertThatThrownBy(() -> new ScheduleResyncProperties(Duration.ofSeconds(30))).hasMessageContaining("periodic");
        assertThatThrownBy(() -> new ScheduleResyncProperties(Duration.ofSeconds(-1))).hasMessageContaining("periodic");
    }
}
