package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrakeTest {

    static final BrakeProperties DEFAULTS = new BrakeProperties(null, null, null, null, null, null, null, null, null,
            null, null, null);
    static final RelayOutcome BAD = new RelayOutcome(10, 5);
    static final RelayOutcome GOOD = new RelayOutcome(10, 0);

    private final Brake brake = new Brake(DEFAULTS, new ControlMetrics(new SimpleMeterRegistry(), new Leadership(),
            TestSnapshots.emptyHolder()));
    private Instant now = Instant.parse("2026-10-10T01:00:00Z");
    /** 노드 a 의 누적. 회차마다 이번 초의 결과를 더해 보낸다. */
    private RelayOutcome total = new RelayOutcome(0, 0);

    @BeforeEach
    void setUp() {
        // 오래전에 이어 받은 것으로 두어 유지 기간이 시험을 가리지 않게 한다
        brake.adopt(7, 1.0, now.minusSeconds(3_600));
        brake.next(Map.of("a", total), now);
    }

    private double tick(RelayOutcome thisSecond) {
        now = now.plusSeconds(1);
        total = new RelayOutcome(total.relayed() + thisSecond.relayed(), total.bad() + thisSecond.bad());
        return brake.next(Map.of("a", total), now);
    }

    @Test
    void 나쁜_응답이_선을_넘으면_절반으로_줄이고_유지_기간_동안은_더_줄이지_않으며_바닥_아래로는_안_내린다() {
        tick(BAD);
        assertThat(tick(BAD)).as("표본 20 이 차면 줄인다").isEqualTo(0.5);
        assertThat(tick(BAD)).as("5초 유지").isEqualTo(0.5);
        for (int i = 0; i < 3; i++) {
            tick(BAD);
        }
        assertThat(tick(BAD)).isEqualTo(0.25);
        for (int i = 0; i < 30; i++) {
            tick(BAD);
        }
        assertThat(tick(BAD)).isEqualTo(0.1);
    }

    @Test
    void 표본이_적으면_나빠도_줄이지_않는다() {
        for (int i = 0; i < 10; i++) {
            assertThat(tick(new RelayOutcome(2, 2))).isEqualTo(1.0);
        }
    }

    @Test
    void 좋아진_뒤_10초가_지나면_5초마다_0_1_씩_딱_맞게_운영값까지_되돌린다() {
        tick(BAD);
        tick(BAD);
        for (int i = 0; i < 5; i++) {
            tick(GOOD);
        }
        assertThat(tick(GOOD)).as("나쁜 응답이 창에서 빠진 뒤부터 10초").isEqualTo(0.5);
        List<Double> steps = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double factor = tick(GOOD);
            if (steps.isEmpty() || steps.getLast() != factor) {
                steps.add(factor);
            }
        }
        assertThat(steps).containsExactly(0.5, 0.6, 0.7, 0.8, 0.9, 1.0);
    }

    @Test
    void 나쁨과_좋음_사이면_회복_시계를_다시_센다() {
        tick(BAD);
        tick(BAD);
        for (int i = 0; i < 8; i++) {
            tick(GOOD);
        }
        tick(new RelayOutcome(10, 3));
        tick(new RelayOutcome(10, 3));
        for (int i = 0; i < 9; i++) {
            assertThat(tick(GOOD)).as("중간 구간에서 회복 시계가 처음부터").isEqualTo(0.5);
        }
    }

    @Test
    void 새_임기는_발행된_배율에서_이어_가고_이어_받은_직후에는_유지_기간_동안_더_줄이지_않는다() {
        brake.adopt(8, 0.5, now);
        brake.next(Map.of("a", total), now);

        for (int i = 0; i < 4; i++) {
            assertThat(tick(BAD)).isEqualTo(0.5);
        }
        assertThat(tick(BAD)).isEqualTo(0.25);
    }

    @Test
    void 처음_보는_노드는_뜬_뒤_누적을_한꺼번에_세지_않고_재시작한_노드는_지금_누적만_센다() {
        now = now.plusSeconds(1);
        assertThat(brake.next(Map.of("a", total, "b", new RelayOutcome(1_000, 1_000)), now)).as("b 첫 회차는 기준만").isEqualTo(1.0);
        now = now.plusSeconds(1);
        assertThat(brake.next(Map.of("a", total, "b", new RelayOutcome(30, 30)), now)).as("재시작한 b 의 30/30").isEqualTo(0.5);
    }

    @Test
    void 응답에서_잠깐_빠졌다_돌아온_노드는_빠진_동안의_결과를_잃지_않는다() {
        RelayOutcome b = new RelayOutcome(0, 0);
        now = now.plusSeconds(1);
        brake.next(Map.of("a", total, "b", b), now);
        now = now.plusSeconds(1);
        brake.next(Map.of("a", total), now);
        now = now.plusSeconds(1);

        assertThat(brake.next(Map.of("a", total, "b", new RelayOutcome(30, 30)), now)).isEqualTo(0.5);
    }

    @Test
    void 오래_빠진_노드는_잊고_다시_나타나면_처음_보는_노드로_센다() {
        now = now.plusSeconds(1);
        brake.next(Map.of("a", total, "b", new RelayOutcome(0, 0)), now);
        for (int i = 0; i < 11; i++) {
            now = now.plusSeconds(1);
            brake.next(Map.of("a", total), now);
        }
        now = now.plusSeconds(1);

        assertThat(brake.next(Map.of("a", total, "b", new RelayOutcome(30, 30)), now)).as("기준만 잡는다").isEqualTo(1.0);
    }

    @Test
    void 천분율보다_작은_바닥이나_회복_폭은_받지_않는다() {
        assertThatThrownBy(() -> new BrakeProperties(null, null, null, null, null, null, null, 0.0001, null, null, null, null))
                .hasMessageContaining("0.001");
        assertThatThrownBy(() -> new BrakeProperties(null, null, null, null, null, null, null, null, null, null, 0.0001, null))
                .hasMessageContaining("0.001");
    }

    @Test
    void 끄면_늘_1_이다() {
        Brake off = new Brake(new BrakeProperties(false, null, null, null, null, null, null, null, null, null, null, null),
                new ControlMetrics(new SimpleMeterRegistry(), new Leadership(), TestSnapshots.emptyHolder()));

        assertThat(off.needsAdopt(7)).isFalse();
        assertThat(off.next(Map.of("a", new RelayOutcome(100, 100)), now)).isEqualTo(1.0);
    }

    @Test
    void 배율을_곱해도_일시_정지가_아니면_1_아래로_내리지_않는다() {
        assertThat(Brake.apply(100, 0.5)).isEqualTo(50);
        assertThat(Brake.apply(10, 0.8)).isEqualTo(8);
        assertThat(Brake.apply(3, 0.1)).isEqualTo(1);
        assertThat(Brake.apply(0, 1.0)).as("운영자가 멈춘 것은 그대로").isZero();
    }
}
