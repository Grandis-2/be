package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.RuntimeState;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.queue.QueueState;
import com.grandis.nova.waitingroom.redis.ControlStore;
import com.grandis.nova.waitingroom.redis.ControlStore.ClusterView;
import com.grandis.nova.waitingroom.redis.LuaScripts;
import com.grandis.nova.waitingroom.redis.ProductSchedules;
import com.grandis.nova.waitingroom.redis.QueueStore;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import com.grandis.nova.waitingroom.support.RedisContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationRoundTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static final ControlPlaneProperties PROPERTIES = new ControlPlaneProperties(null, null, null, null, null, null, null, null);

    private ReactiveStringRedisTemplate redis;
    private QueueStore queue;
    private ControlStore control;
    private AllocationRound round;
    private final Leadership leadership = new Leadership();
    /** 재발행 요청을 받은 순서대로 남긴다. */
    private final List<String> resyncReasons = new CopyOnWriteArrayList<>();
    private Instant now;

    @BeforeEach
    void setUp() {
        redis = RedisContainer.fresh();
        LuaScripts scripts = new LuaScripts(redis);
        queue = new QueueStore(scripts);
        control = new ControlStore(scripts, redis);
        round = newRound();
        now = Instant.now();
    }

    /** 리더가 바뀌면 새 노드는 빈 기억으로 시작한다. */
    private AllocationRound newRound() {
        ControlMetrics metrics = new ControlMetrics(new SimpleMeterRegistry(), leadership,
                new SnapshotHolder(new RedisClock(Clock.systemUTC()), PROPERTIES));
        return new AllocationRound(control, new AdmissionProperties(10L, 0.7), PROPERTIES, metrics, leadership,
                new ScheduleResync(control, reason -> Mono.fromRunnable(() -> resyncReasons.add(reason)), metrics));
    }

    private void schedule(String product, Instant opensAt, Instant closesAt) {
        redis.opsForHash().put(RedisKeys.PRODUCTS, product,
                ProductSchedules.format(new SalesWindow(opensAt, closesAt), 1)).block(WAIT);
    }

    private void line(String product, int count) {
        IntStream.range(0, count).forEach(i -> queue.enqueue(product, product + "-" + i, QueueStore.UNLIMITED, now).block(WAIT));
    }

    private long admitted(String product, int count) {
        return IntStream.range(0, count)
                .filter(i -> queue.status(product, product + "-" + i, now).block(WAIT).entry().state() == QueueState.ADMITTED)
                .count();
    }

    private GatewaySnapshot run(long fence, long idlePasses) {
        return run(fence, idlePasses, 0);
    }

    private GatewaySnapshot run(long fence, long idlePasses, long tick) {
        leadership.hold(fence, leadership.nanoTime(), Duration.ofHours(1));
        return round.run(fence, new ClusterView(2, idlePasses), tick).block(WAIT);
    }

    @Test
    void 같은_회차를_다시_돌려도_한_번만_들이고_다음_회차는_다시_들인다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));
        line("101", 30);

        run(7, 0, 1);
        run(7, 0, 1);
        assertThat(admitted("101", 30)).as("같은 회차 재시도").isEqualTo(10);
        run(7, 0, 2);
        assertThat(admitted("101", 30)).isEqualTo(20);
    }

    @Test
    void 전역_속도를_줄_선_모델에_나눠_앞사람부터_입장시키고_판정_재료를_발행한다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));
        schedule("202", now.minusSeconds(60), now.plusSeconds(3_600));
        line("101", 20);
        line("202", 20);

        GatewaySnapshot snapshot = run(7, 0);

        assertThat(admitted("101", 20) + admitted("202", 20)).isEqualTo(10);
        assertThat(queue.status("101", "101-0", now).block(WAIT).entry().state()).isEqualTo(QueueState.ADMITTED);
        assertThat(snapshot.product("101").orElseThrow().credit()).isEqualTo(5);
        assertThat(snapshot.product("101").orElseThrow().waiting()).isEqualTo(15);
        assertThat(snapshot.meta().gatewayCount()).isEqualTo(2);
        assertThat(SnapshotCodec.decode(control.readSnapshot().block(WAIT).entries())).contains(snapshot);
    }

    @Test
    void 직전_1초_한산_통과만큼_줄_배분을_줄인다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));
        line("101", 20);

        run(7, 6);

        assertThat(admitted("101", 20)).as("전역 10 − 한산 통과 6").isEqualTo(4);
    }

    @Test
    void 운영값의_전역_속도와_모델_상한을_따른다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));
        line("101", 50);
        redis.opsForHash().put(RedisKeys.SETTINGS, OperationalSettings.GLOBAL_CREDIT, "30").block(WAIT);
        redis.opsForHash().put(RedisKeys.SETTINGS, RedisKeys.capField("101"), "8").block(WAIT);

        GatewaySnapshot snapshot = run(7, 0);

        assertThat(admitted("101", 50)).isEqualTo(8);
        assertThat(snapshot.product("101").orElseThrow().cap()).isEqualTo(8);
        assertThat(snapshot.meta().globalCredit()).isEqualTo(30);
    }

    @Test
    void 오픈_전이거나_마감된_모델에는_배분하지_않는다() {
        schedule("101", now.plusSeconds(600), now.plusSeconds(3_600));
        schedule("202", now.minusSeconds(3_600), now.minusSeconds(60));
        line("202", 5);

        GatewaySnapshot snapshot = run(7, 0);

        assertThat(admitted("202", 5)).isZero();
        assertThat(snapshot.product("202").orElseThrow().runtime()).isEqualTo(RuntimeState.CLOSED);
        assertThat(snapshot.product("101").orElseThrow().runtime()).isEqualTo(RuntimeState.IDLE);
    }

    @Test
    void 한_모델의_커서가_깨져도_나머지_모델은_배분하고_발행한다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));
        schedule("202", now.minusSeconds(60), now.plusSeconds(3_600));
        line("101", 5);
        line("202", 5);
        redis.opsForValue().set("wr:admitted:{101}", "broken").block(WAIT);

        GatewaySnapshot snapshot = run(7, 0);

        assertThat(admitted("202", 5)).isEqualTo(5);
        assertThat(snapshot.products()).containsKeys("101", "202");
    }

    @Test
    void 마감_유예가_지나면_줄과_생존_신호를_지우고_입장_표시는_보관_기간_뒤_사라지게_한다() {
        schedule("101", now.minus(Duration.ofHours(1)), now.minus(PROPERTIES.closeGrace()).minusSeconds(1));
        line("101", 3);
        redis.opsForHash().put("wr:grace:{101}", "x", "a:" + now.getEpochSecond()).block(WAIT);

        run(7, 0);
        run(7, 0);

        assertThat(redis.hasKey("wr:queue:{101}").block(WAIT)).isFalse();
        assertThat(redis.hasKey("wr:alive:{101}").block(WAIT)).isFalse();
        assertThat(redis.getExpire("wr:grace:{101}").block(WAIT)).isPositive();
    }

    @Test
    void 오래된_모델도_줄을_지운_것을_확인한_뒤에야_Redis_를_치지_않고_마감으로만_발행한다() {
        schedule("101", now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        line("101", 3);

        assertThat(run(7, 0).product("101").orElseThrow().waiting()).as("첫 회차는 표만 세워 아직 읽는다").isEqualTo(3);
        assertThat(redis.hasKey("wr:queue:{101}").block(WAIT)).as("시각이 지났다고 정리를 건너뛰지 않는다").isTrue();
        run(7, 0);
        assertThat(redis.hasKey("wr:queue:{101}").block(WAIT)).isFalse();

        line("101", 2);
        GatewaySnapshot snapshot = run(7, 0);
        assertThat(snapshot.product("101").orElseThrow().runtime()).isEqualTo(RuntimeState.CLOSED);
        assertThat(snapshot.product("101").orElseThrow().waiting()).as("은퇴 뒤에는 줄 길이를 읽지 않는다").isZero();
        assertThat(redis.hasKey("wr:queue:{101}").block(WAIT)).as("정리도 하지 않는다").isTrue();
    }

    @Test
    void 줄_정리가_막히면_은퇴하지_않고_다음_회차에_줄_길이를_다시_읽고_정리를_다시_한다() {
        schedule("101", now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        line("101", 3);
        redis.opsForValue().set("wr:closefence:{101}", "100").block(WAIT);

        run(7, 0);
        run(7, 0);
        assertThat(redis.hasKey("wr:queue:{101}").block(WAIT)).as("더 큰 임기의 표에 막혔다").isTrue();
        assertThat(run(7, 0).product("101").orElseThrow().waiting()).as("은퇴하지 않고 다시 읽는다").isEqualTo(3);

        redis.delete("wr:closefence:{101}").block(WAIT);
        run(7, 0);
        run(7, 0);
        assertThat(redis.hasKey("wr:queue:{101}").block(WAIT)).as("막힘이 풀리면 다시 정리한다").isFalse();
    }

    @Test
    void 리더가_바뀌면_새_리더는_은퇴_시각이_지났어도_줄을_지운_것을_스스로_확인한_뒤에야_은퇴시킨다() {
        schedule("101", now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        line("101", 3);
        run(7, 0);
        run(7, 0);
        line("101", 2);
        assertThat(run(7, 0).product("101").orElseThrow().waiting()).as("옛 리더는 은퇴시켰다").isZero();

        round = newRound();

        assertThat(run(8, 0).product("101").orElseThrow().waiting()).as("새 리더는 다시 읽는다").isEqualTo(2);
        run(8, 0);
        assertThat(redis.hasKey("wr:queue:{101}").block(WAIT)).isFalse();
        line("101", 1);
        assertThat(run(8, 0).product("101").orElseThrow().waiting()).as("확인한 뒤에야 은퇴").isZero();
    }

    @Test
    void 일정을_하나도_모르면_재발행을_요청하고_조용한_기간_안에는_다시_요청하지_않는다() {
        run(7, 0);
        await(() -> Boolean.TRUE.equals(redis.hasKey(RedisKeys.RESYNC_REQUESTED).toFuture().join()));

        run(7, 0);
        round = newRound();
        run(8, 0);
        // 다시 보냈다면 표식을 다시 세우기 전 요청부터 남는다 — 요청 기록과 횟수로 본다
        await(() -> "1".equals(redis.opsForValue().get(RedisKeys.RESYNC_ATTEMPTS).toFuture().join()));

        assertThat(resyncReasons).as("리더가 바뀌어도 표식이 있으면 다시 요청하지 않는다").containsExactly("SCHEDULE_EMPTY");
        assertThat(redis.getExpire(RedisKeys.RESYNC_REQUESTED).block(WAIT)).isLessThanOrEqualTo(ScheduleResync.FIRST_QUIET);
    }

    @Test
    void 연달아_비어_있으면_요청_간격을_두_배씩_상한까지_늘린다() {
        assertThat(ScheduleResync.quietAfter(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(ScheduleResync.quietAfter(2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(ScheduleResync.quietAfter(4)).isEqualTo(Duration.ofMinutes(40));
        assertThat(ScheduleResync.quietAfter(5)).isEqualTo(ScheduleResync.MAX_QUIET);
        assertThat(ScheduleResync.quietAfter(100)).isEqualTo(ScheduleResync.MAX_QUIET);
    }

    @Test
    void 일정을_알게_되면_연속_횟수를_지워_다음에는_처음_간격부터_요청한다() {
        redis.opsForValue().set(RedisKeys.RESYNC_ATTEMPTS, "4").block(WAIT);
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));

        run(7, 0);

        await(() -> !Boolean.TRUE.equals(redis.hasKey(RedisKeys.RESYNC_ATTEMPTS).toFuture().join()));
    }

    @Test
    void 일정을_아는_동안은_재발행을_요청하지_않는다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));

        run(7, 0);

        assertThat(control.resyncRequestedRecently().block(WAIT)).isFalse();
        assertThat(resyncReasons).isEmpty();
    }

    @Test
    void 마감_뒤_오래된_모델은_정리를_확인한_뒤_은퇴_표식으로_바꿔_배분과_판정_재료에서_뺀다() {
        schedule("101", now.minus(Duration.ofDays(9)), now.minus(AllocationRound.FORGET_AFTER).minusSeconds(1));
        schedule("202", now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)));

        run(7, 0);
        run(7, 0);
        assertThat(product("101")).as("정리를 확인한 회차까지는 일정 그대로다").doesNotStartWith("retired|");
        run(7, 0);

        assertThat(product("101")).startsWith("retired|");
        assertThat(run(7, 0).products()).as("은퇴한 모델은 발행하지 않는다").doesNotContainKey("101").containsKey("202");
        assertThat(product("202")).as("7일 전이면 마감으로 남긴다").doesNotStartWith("retired|");
    }

    @Test
    void 은퇴_표식은_재전달을_막을_기간이_지나면_지운다() {
        schedule("101", now.minus(Duration.ofDays(1)), now.plusSeconds(3_600));
        redis.opsForHash().put(RedisKeys.PRODUCTS, "202",
                "retired|3|" + now.minus(AllocationRound.TOMBSTONE_TTL).minusSeconds(1).toEpochMilli()).block(WAIT);
        redis.opsForHash().put(RedisKeys.PRODUCTS, "303", "retired|1|" + now.toEpochMilli()).block(WAIT);

        run(7, 0);

        assertThat(product("202")).isNull();
        assertThat(product("303")).as("아직 막아야 한다").startsWith("retired|");
    }

    private String product(String key) {
        return (String) redis.opsForHash().get(RedisKeys.PRODUCTS, key).block(WAIT);
    }

    private static void await(BooleanSupplier condition) {
        StepVerifier.create(Flux.interval(Duration.ofMillis(20)).filter(tick -> condition.getAsBoolean()).next())
                .expectNextCount(1).expectComplete().verify(WAIT);
    }

    @Test
    void 깨진_일정은_그_모델만_빼고_나머지는_돈다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));
        redis.opsForHash().put(RedisKeys.PRODUCTS, "202", "broken").block(WAIT);
        redis.opsForHash().put(RedisKeys.PRODUCTS, "{bad}", "1|2|3").block(WAIT);

        GatewaySnapshot snapshot = run(7, 0);

        assertThat(snapshot.products()).containsOnlyKeys("101");
    }

    @Test
    void 옛_임기의_회차는_입장도_발행도_못_하고_리더를_잃었다고_알린다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));
        line("101", 30);
        GatewaySnapshot published = run(9, 0);
        long before = admitted("101", 30);

        assertThatThrownBy(() -> run(8, 0)).isInstanceOf(AllocationRound.LostLeadershipException.class);
        assertThat(admitted("101", 30)).as("커서 그대로").isEqualTo(before);
        assertThat(SnapshotCodec.decode(control.readSnapshot().block(WAIT).entries())).as("판정 재료 그대로").contains(published);
    }

    @Test
    void 리스를_믿을_수_없으면_커서를_올리기_전에_멈춘다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));
        line("101", 5);
        leadership.hold(7, leadership.nanoTime(), Duration.ofHours(1));
        leadership.lose();

        assertThatThrownBy(() -> round.run(7, new ClusterView(1, 0), 0).block(WAIT))
                .isInstanceOf(AllocationRound.LostLeadershipException.class);
        assertThat(admitted("101", 5)).isZero();
    }

    @Test
    void 몫이_0_이어도_사라진_커서는_이_리더가_본_값으로_되살려_입장한_사람이_대기로_돌아가지_않는다() {
        schedule("101", now.minusSeconds(60), now.plusSeconds(3_600));
        line("101", 5);
        redis.opsForHash().put(RedisKeys.SETTINGS, OperationalSettings.GLOBAL_CREDIT, "2").block(WAIT);
        run(7, 0);
        redis.delete("wr:admitted:{101}").block(WAIT);
        redis.opsForHash().put(RedisKeys.SETTINGS, OperationalSettings.GLOBAL_CREDIT, "0").block(WAIT);

        run(7, 0);

        assertThat(admitted("101", 5)).isEqualTo(2);
    }
}
