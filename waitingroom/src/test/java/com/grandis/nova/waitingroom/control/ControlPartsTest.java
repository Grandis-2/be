package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPartsTest {

    static final Instant OPENS_AT = Instant.parse("2026-10-10T01:00:00Z");
    static final SalesWindow WINDOW = new SalesWindow(OPENS_AT, OPENS_AT.plusSeconds(3_600));

    @Nested
    class 판정_재료_형식 {

        private final GatewaySnapshot snapshot = new GatewaySnapshot(
                Map.of("101", ProductState.withQueue(5, 20, WINDOW, 8), "202", ProductState.idle(WINDOW, ProductState.UNLIMITED_CAP)),
                new SnapshotMeta(100, 3, MaxWait.of(Duration.ofMinutes(10))), 1_790_000_000_000L);

        @Test
        void 쓰고_읽으면_같다() {
            assertThat(SnapshotCodec.decode(SnapshotCodec.encode(snapshot))).contains(snapshot);
        }

        @Test
        void 깨진_모델은_그_모델만_빼고_전역_값이_없으면_통째로_안_읽는다() {
            Map<String, String> fields = new HashMap<>(SnapshotCodec.encode(snapshot));
            fields.put("p:202", "broken");

            assertThat(SnapshotCodec.decode(fields).orElseThrow().products()).containsOnlyKeys("101");
            fields.remove(SnapshotCodec.GLOBAL_CREDIT);
            assertThat(SnapshotCodec.decode(fields)).isEmpty();
        }
    }

    @Nested
    class 운영값 {

        @Test
        void 없거나_깨진_값은_기본값이고_모델_상한은_양수만_받는다() {
            OperationalSettings settings = OperationalSettings.from(Map.of(
                    OperationalSettings.GLOBAL_CREDIT, "abc", OperationalSettings.MAX_WAIT_SEC, "-5",
                    "cap:101", "7", "cap:202", "0", "cap:{x}", "9"), 100);

            assertThat(settings.globalCredit()).isEqualTo(100);
            assertThat(settings.maxWait()).isEqualTo(MaxWait.unlimited());
            assertThat(settings.capOf("101")).isEqualTo(7);
            assertThat(settings.capOf("202")).isEqualTo(ProductState.UNLIMITED_CAP);
        }

        @Test
        void 전역_속도_0_은_입장을_멈추는_운영값이라_받는다() {
            assertThat(OperationalSettings.from(Map.of(OperationalSettings.GLOBAL_CREDIT, "0"), 100).globalCredit()).isZero();
        }
    }

    @Nested
    class 전달_결과_집계 {

        @Test
        void 여러_스레드가_동시에_기록해도_회원마다_1초에_한_번씩_빠짐없이_센다() throws Exception {
            RelayOutcomeCounter counter = new RelayOutcomeCounter();
            try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
                List<Callable<Void>> calls = new ArrayList<>();
                for (int customer = 0; customer < 200; customer++) {
                    String id = String.valueOf(customer);
                    for (int repeat = 0; repeat < 5; repeat++) {
                        calls.add(() -> {
                            counter.record(100, id, id.endsWith("0"));
                            return null;
                        });
                    }
                }
                for (Future<Void> done : pool.invokeAll(calls)) {
                    done.get();
                }
            }

            assertThat(counter.totals()).as("회원 200명, 그중 0 으로 끝나는 20명은 나쁨").isEqualTo(new RelayOutcome(200, 20));
        }

        @Test
        void 초가_바뀌면_같은_회원을_다시_센다() {
            RelayOutcomeCounter counter = new RelayOutcomeCounter();
            counter.record(100, "1", false);
            counter.record(100, "1", true);
            counter.record(101, "1", true);

            assertThat(counter.totals()).isEqualTo(new RelayOutcome(2, 1));
        }
    }

    @Nested
    class 한산_통과_수 {

        @Test
        void 직전_1초의_수만_돌려주고_초를_건너뛰면_0_이다() {
            IdlePassCounter counter = new IdlePassCounter();
            counter.record(100);
            counter.record(100);
            counter.record(101);

            assertThat(counter.lastSecond(101)).isEqualTo(2);
            assertThat(counter.lastSecond(102)).isEqualTo(1);
            assertThat(counter.lastSecond(105)).isZero();
        }
    }

    @Nested
    class 시계와_낡음 {

        @Test
        void Redis_시각과의_차이를_배워_보정하고_발행_뒤_나이로_낡음을_판정한다() {
            Clock local = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC);
            RedisClock clock = new RedisClock(local);
            clock.learn(1_003_000, 999_900, 1_000_100);
            SnapshotHolder holder = new SnapshotHolder(clock, new ControlPlaneProperties(null, null, null, null, null, null, null, null));

            assertThat(clock.now()).isEqualTo(Instant.ofEpochMilli(1_003_000));
            assertThat(holder.stale()).as("처음 받기 전").isTrue();
            holder.replace(new GatewaySnapshot(Map.of(), new SnapshotMeta(1, 1, MaxWait.unlimited()), 999_000));
            assertThat(holder.stale()).isFalse();
            holder.replace(new GatewaySnapshot(Map.of(), new SnapshotMeta(1, 1, MaxWait.unlimited()), 990_000));
            assertThat(holder.current().orElseThrow().publishedAtMillis()).as("옛 재료로 되돌리지 않는다").isEqualTo(999_000);

            RedisClock later = new RedisClock(Clock.fixed(Instant.ofEpochMilli(1_006_000), ZoneOffset.UTC));
            later.learn(1_009_000, 1_006_000, 1_006_000);
            SnapshotHolder aged = new SnapshotHolder(later, new ControlPlaneProperties(null, null, null, null, null, null, null, null));
            aged.replace(new GatewaySnapshot(Map.of(), new SnapshotMeta(1, 1, MaxWait.unlimited()), 1_003_000));
            assertThat(aged.stale()).as("5초를 넘겼다").isTrue();
        }
    }

    @Nested
    class 리더_임기 {

        @Test
        void 연장_요청부터_믿는_기간이_지나면_Redis_응답_없이도_리더가_아니다() {
            long[] now = {1_000};
            Leadership leadership = new Leadership(() -> now[0]);

            assertThat(leadership.hold(7, 1_000, Duration.ofNanos(500))).isTrue();
            assertThat(leadership.fence()).isEqualTo(7);
            now[0] = 1_499;
            assertThat(leadership.isLeader()).isTrue();
            now[0] = 1_500;
            assertThat(leadership.fence()).isZero();
            assertThat(leadership.hold(7, 1_500, Duration.ofNanos(500))).as("같은 임기 연장은 새 리더가 아니다").isFalse();
        }

        @Test
        void 옛_임기를_내려놓을_때_그사이_받은_새_임기는_지우지_않는다() {
            Leadership leadership = new Leadership(() -> 0);
            leadership.hold(8, 0, Duration.ofSeconds(1));

            leadership.lose(7);
            assertThat(leadership.fence()).isEqualTo(8);
            leadership.lose(8);
            assertThat(leadership.fence()).isZero();
        }
    }

    @Nested
    class 시계_측정 {

        @Test
        void 왕복이_짧은_측정을_믿고_오래되면_새로_잰다() {
            RedisClock clock = new RedisClock(Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC));
            clock.learn(2_000_000, 999_990, 1_000_010);
            clock.learn(2_500_000, 999_000, 1_001_000);
            assertThat(clock.now()).as("왕복 2초짜리 측정은 무시").isEqualTo(Instant.ofEpochMilli(2_000_000));

            clock.learn(3_000_000, 1_040_000, 1_042_000);
            assertThat(clock.now()).as("30초가 지나면 왕복이 길어도 바꾼다").isEqualTo(Instant.ofEpochMilli(1_000_000 + 3_000_000 - 1_041_000));
        }

        @Test
        void 가진_재료가_신선하면_시각이_더_이른_재료는_버린다() {
            RedisClock clock = new RedisClock(Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC));
            clock.learn(100_000, 100_000, 100_000);
            SnapshotHolder holder = new SnapshotHolder(clock, new ControlPlaneProperties(null, null, null, null, null, null, null, null));
            holder.replace(new GatewaySnapshot(Map.of(), new SnapshotMeta(1, 1, MaxWait.unlimited()), 99_000));

            assertThat(holder.replace(new GatewaySnapshot(Map.of(), new SnapshotMeta(2, 1, MaxWait.unlimited()), 98_000))).isFalse();
            assertThat(holder.current().orElseThrow().meta().globalCredit()).isEqualTo(1);
        }

        @Test
        void 가진_재료가_낡았으면_시각이_더_이른_재료도_받는다() {
            RedisClock clock = new RedisClock(Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC));
            clock.learn(100_000, 100_000, 100_000);
            SnapshotHolder holder = new SnapshotHolder(clock, new ControlPlaneProperties(null, null, null, null, null, null, null, null));
            holder.replace(new GatewaySnapshot(Map.of(), new SnapshotMeta(1, 1, MaxWait.unlimited()), 90_000));

            assertThat(holder.replace(new GatewaySnapshot(Map.of(), new SnapshotMeta(2, 1, MaxWait.unlimited()), 80_000))).isTrue();
            assertThat(holder.current().orElseThrow().meta().globalCredit()).isEqualTo(2);
        }
    }

    @Test
    void 주기와_수명의_관계가_어긋나면_기동을_막는다() {
        assertThatThrownBy(() -> new ControlPlaneProperties(Duration.ofSeconds(2), Duration.ofSeconds(1),
                null, null, null, null, null, null)).hasMessageContaining("leader-renew");
        assertThatThrownBy(() -> new ControlPlaneProperties(null, null, null, Duration.ofMillis(1_200),
                null, null, null, null)).hasMessageContaining("snapshot-stale-after");
        assertThatThrownBy(() -> new ControlPlaneProperties(null, null, null, null, null, Duration.ofSeconds(90),
                null, null)).hasMessageContaining("close-grace");
        assertThatThrownBy(() -> new ControlPlaneProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), null,
                Duration.ofSeconds(20), null, null, null, null)).hasMessageContaining("네 배");
    }
}
