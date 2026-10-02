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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private Instant now;

    @BeforeEach
    void setUp() {
        redis = RedisContainer.fresh();
        LuaScripts scripts = new LuaScripts(redis);
        queue = new QueueStore(scripts);
        control = new ControlStore(scripts, redis);
        round = new AllocationRound(control, new AdmissionProperties(10L, 0.7), PROPERTIES,
                new ControlMetrics(new SimpleMeterRegistry(), leadership, new SnapshotHolder(new RedisClock(Clock.systemUTC()), PROPERTIES)),
                leadership);
        now = Instant.now();
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
        leadership.hold(fence, leadership.nanoTime(), Duration.ofHours(1));
        return round.run(fence, new ClusterView(2, idlePasses), 0).block(WAIT);
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
