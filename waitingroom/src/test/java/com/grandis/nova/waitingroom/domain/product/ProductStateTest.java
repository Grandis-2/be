package com.grandis.nova.waitingroom.domain.product;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductStateTest {

    static final Instant OPENS_AT = Instant.parse("2026-10-10T01:00:00Z");
    static final SalesWindow WINDOW = new SalesWindow(OPENS_AT, OPENS_AT.plus(Duration.ofHours(14)));
    static final long NO_CAP = ProductState.UNLIMITED_CAP;

    @Nested
    class 불변식 {

        @Test
        void IDLE_은_배분도_줄도_없다() {
            assertThatThrownBy(() -> new ProductState(RuntimeState.IDLE, 5, 0, NO_CAP, WINDOW)).hasMessageContaining("IDLE 이면");
            assertThatThrownBy(() -> new ProductState(RuntimeState.IDLE, 0, 3, NO_CAP, WINDOW)).hasMessageContaining("IDLE 이면");
        }

        @Test
        void 줄이_비었는데_대기열_상태면_유령이다() {
            assertThatThrownBy(() -> new ProductState(RuntimeState.QUEUEING, 5, 0, NO_CAP, WINDOW)).hasMessageContaining("waiting 이 0 이면");
        }

        @Test
        void DRAINING_과_QUEUEING_은_credit_과_waiting_으로_갈린다() {
            assertThatThrownBy(() -> new ProductState(RuntimeState.DRAINING, 5, 10, NO_CAP, WINDOW)).hasMessageContaining("DRAINING 이면");
            assertThatThrownBy(() -> new ProductState(RuntimeState.QUEUEING, 10, 10, NO_CAP, WINDOW)).hasMessageContaining("QUEUEING 이면");
            assertThat(ProductState.withQueue(10, 10, WINDOW, NO_CAP).runtime()).isEqualTo(RuntimeState.DRAINING);
            assertThat(ProductState.withQueue(9, 10, WINDOW, NO_CAP).runtime()).isEqualTo(RuntimeState.QUEUEING);
        }

        @Test
        void 마감은_배분이_없고_credit_은_모델_상한을_넘지_않는다() {
            assertThatThrownBy(() -> new ProductState(RuntimeState.CLOSED, 1, 3, NO_CAP, WINDOW)).hasMessageContaining("CLOSED 면");
            assertThatThrownBy(() -> ProductState.withQueue(11, 20, WINDOW, 10)).hasMessageContaining("모델 상한을 넘지");
            assertThat(ProductState.closed(3, WINDOW, NO_CAP).credit()).isZero();
        }
    }

    @Nested
    class 접수_기간 {

        @Test
        void 오픈_시각_이상_마감_시각_미만에만_연다() {
            ProductState idle = ProductState.idle(WINDOW, NO_CAP);

            assertThat(idle.phaseAt(OPENS_AT.minusMillis(1))).isEqualTo(SalesPhase.BEFORE_OPEN);
            assertThat(idle.phaseAt(OPENS_AT)).isEqualTo(SalesPhase.OPEN);
            assertThat(idle.phaseAt(WINDOW.closesAt().minusMillis(1))).isEqualTo(SalesPhase.OPEN);
            assertThat(idle.phaseAt(WINDOW.closesAt())).isEqualTo(SalesPhase.CLOSED);
        }

        @Test
        void 리더가_닫았으면_기간_안이어도_마감이다() {
            assertThat(ProductState.closed(0, WINDOW, NO_CAP).phaseAt(OPENS_AT.plusSeconds(1))).isEqualTo(SalesPhase.CLOSED);
        }

        @Test
        void 마감이_오픈보다_앞설_수_없다() {
            assertThatThrownBy(() -> new SalesWindow(OPENS_AT, OPENS_AT)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class 한산_상한 {

        private final SnapshotMeta meta = new SnapshotMeta(100, 4, MaxWait.unlimited());

        @Test
        void 노드_몫_전역_속도에_비율을_곱한다() {
            assertThat(ProductState.idle(WINDOW, NO_CAP).idleCap(meta, 0.7)).isEqualTo(17);
        }

        @Test
        void 모델_상한의_노드_몫을_넘지_않아_노드_합이_상한_이하다() {
            assertThat(ProductState.idle(WINDOW, 20).idleCap(meta, 0.7)).isEqualTo(5);
            assertThat(ProductState.idle(WINDOW, 2).idleCap(meta, 0.7))
                    .as("상한 2 를 노드 4 대가 나누면 한산 통과는 꺼지고 줄로 간다").isZero();
        }

        @Test
        void 절삭으로_0_이_되면_1_이고_비율_0_은_한산_통과를_끈다() {
            SnapshotMeta small = new SnapshotMeta(4, 4, MaxWait.unlimited());

            assertThat(ProductState.idle(WINDOW, NO_CAP).idleCap(small, 0.5)).isEqualTo(1);
            assertThat(ProductState.idle(WINDOW, NO_CAP).idleCap(small, 0)).isZero();
        }

        @Test
        void 노드_수를_모르면_한_대로_본다() {
            assertThat(new SnapshotMeta(100, 0, MaxWait.unlimited()).globalCapPerNode()).isEqualTo(100);
        }
    }

    @Nested
    class 줄_상한 {

        @Test
        void 기본은_제한_없음이다() {
            assertThat(ProductState.withQueue(10, 50, WINDOW, NO_CAP).queueCapacity(MaxWait.unlimited()))
                    .isEqualTo(Long.MAX_VALUE);
        }

        @Test
        void 최대_대기_시간을_정하면_속도_곱하기_시간이다() {
            MaxWait tenMinutes = MaxWait.of(Duration.ofMinutes(10));

            assertThat(ProductState.withQueue(10, 50, WINDOW, NO_CAP).queueCapacity(tenMinutes)).isEqualTo(6_000);
            assertThat(tenMinutes.capacity(Long.MAX_VALUE)).as("곱이 넘치면 제한 없음").isEqualTo(Long.MAX_VALUE);
        }

        @Test
        void 최대_대기_시간은_1초_이상이다() {
            assertThatThrownBy(() -> MaxWait.of(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MaxWait.of(Duration.ofMillis(500))).as("초로 곱하면 0").isInstanceOf(IllegalArgumentException.class);
            assertThat(MaxWait.of(Duration.ofSeconds(1)).capacity(5)).isEqualTo(5);
        }
    }
}
