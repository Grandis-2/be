package com.grandis.nova.waitingroom.domain.queue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueuePoliciesTest {

    @Nested
    class 예상_대기_시간 {

        @Test
        void 앞_인원을_배분된_초당_입장_인원으로_나누고_맨_앞은_0초다() {
            assertThat(EtaPolicy.etaSec(0, 0)).isZero();
            assertThat(EtaPolicy.etaSec(100, 20)).isEqualTo(5.0);
        }

        @Test
        void 배수율을_모르면_0_이_아니라_가장_넓은_구간으로_말한다() {
            double unknown = EtaPolicy.etaSec(100, 0);

            assertThat(unknown).isNaN();
            assertThat(EtaPolicy.reportSec(unknown)).isEqualTo(450);
            assertThat(EtaPolicy.bucket(unknown)).isEqualTo(EtaDisplay.LONG_WAIT);
            assertThat(EtaPolicy.bucket(-1)).isEqualTo(EtaDisplay.LONG_WAIT);
            assertThat(EtaPolicy.reportSec(Double.POSITIVE_INFINITY)).isEqualTo(450);
        }

        @Test
        void 구간_경계는_아래쪽에_속한다() {
            assertThat(EtaPolicy.bucket(29.9)).isEqualTo(EtaDisplay.ALMOST_THERE);
            assertThat(EtaPolicy.bucket(30)).isEqualTo(EtaDisplay.ABOUT_A_MINUTE);
            assertThat(EtaPolicy.bucket(90)).isEqualTo(EtaDisplay.ABOUT_FIVE_MINUTES);
            assertThat(EtaPolicy.bucket(450)).isEqualTo(EtaDisplay.LONG_WAIT);
        }

        @Test
        void 앞_인원이_음수면_어댑터가_깨진_것이다() {
            assertThatThrownBy(() -> EtaPolicy.etaSec(-1, 10)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class 조회_간격 {

        private final PollIntervalPolicy exact = PollIntervalPolicy.of(0);

        @Test
        void 흔들림_비율은_1_미만이다() {
            assertThatThrownBy(() -> PollIntervalPolicy.of(1.0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 예상_대기_시간이_길수록_드물게_묻고_모르면_가장_드물게_묻는다() {
            assertThat(exact.intervalSec(0, () -> 0.5)).isEqualTo(1);
            assertThat(exact.intervalSec(5, () -> 0.5)).isEqualTo(3);
            assertThat(exact.intervalSec(30, () -> 0.5)).isEqualTo(10);
            assertThat(exact.intervalSec(120, () -> 0.5)).isEqualTo(30);
            assertThat(exact.intervalSec(Double.NaN, () -> 0.5)).isEqualTo(30);
            assertThat(exact.intervalSec(-1, () -> 0.5)).isEqualTo(30);
        }

        @Test
        void 흔들어도_1초에서_60초_사이고_같은_밴드가_한_값으로_모이지_않는다() {
            PollIntervalPolicy standard = PollIntervalPolicy.standard();

            assertThat(standard.intervalSec(0, () -> 0.0)).isEqualTo(1);
            assertThat(standard.intervalSec(0, () -> 1.0)).isEqualTo(2);
            assertThat(standard.intervalSec(200, () -> 0.0)).isLessThan(standard.intervalSec(200, () -> 1.0));
            assertThat(standard.intervalSec(200, () -> 1.0)).isLessThanOrEqualTo(60);
        }

        @Test
        void 생존_신호는_최대_간격으로_세_번_놓쳐도_남는다() {
            assertThat(PollIntervalPolicy.aliveTtl())
                    .isGreaterThan(PollIntervalPolicy.maxInterval().multipliedBy(4))
                    .isEqualTo(Duration.ofSeconds(250));
        }
    }

    @Nested
    class 줄_자리 {

        @Test
        void 상태마다_가질_수_없는_값은_만들지_않는다() {
            assertThatThrownBy(() -> new QueueEntry(QueueState.WAITING, 5, 10, false, false, false, 5))
                    .as("총원은 나를 포함해 앞 인원보다 크다").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new QueueEntry(QueueState.NOT_QUEUED, 0, 10, false, false, false, -1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new QueueEntry(QueueState.ADMITTED, 3, 10, false, false, false, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 내_뒤_인원은_총원에서_앞_인원과_나를_뺀_값이다() {
            assertThat(new QueueEntry(QueueState.WAITING, 3, 10, false, false, false, 10).behind()).isEqualTo(6);
            assertThat(QueueEntry.withoutTotal(QueueState.WAITING, 3, 10, false, false, false).behind())
                    .isEqualTo(QueueEntry.UNKNOWN_TOTAL);
        }

        @Test
        void 다시_선_표시는_새로_선_대기자에게만_남는다() {
            assertThat(QueueEntry.withoutTotal(QueueState.WAITING, 0, 1, false, false, true).rejoined()).isTrue();
            assertThat(QueueEntry.withoutTotal(QueueState.WAITING, 0, 1, true, false, true).rejoined()).isFalse();
            assertThat(QueueEntry.withoutTotal(QueueState.ADMITTED, 0, 1, false, false, true).rejoined()).isFalse();
        }

        @Test
        void 거절만_자리를_못_받은_것이다() {
            assertThat(QueueEntry.rejected().accepted()).isFalse();
            assertThat(QueueEntry.notQueued().accepted()).isTrue();
        }
    }
}
