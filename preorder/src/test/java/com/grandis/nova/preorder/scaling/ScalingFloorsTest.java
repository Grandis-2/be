package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.ScalingDemand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScalingFloorsTest {

    static final ScalingProperties PROPERTIES = properties(false, "", 10);
    static final int ACCEPT_PER_SECOND = 100;

    @Test
    void 걸린_회차가_없으면_평소_대수() {
        assertThat(of(new ScalingDemand(0, null))).isEqualTo(new ScalingFloors(1, 1, false));
    }

    /** 겹친 회차 하나가 끝나도 다른 회차 몫을 내리지 않게 회차마다가 아니라 가장 큰 예상 방문자 수로 맞춘다. */
    @Test
    void 대기열은_가장_큰_예상_방문자_수로_preorder_는_고정_대수() {
        assertThat(of(new ScalingDemand(3, 20_000)))
                .isEqualTo(new ScalingFloors(3, 4, false));
        assertThat(of(new ScalingDemand(1, 15_001))).isEqualTo(new ScalingFloors(3, 4, false));
    }

    @Test
    void 예상_방문자_수가_없거나_적어도_대기열은_surge_까지_올린다() {
        assertThat(of(new ScalingDemand(2, null))).isEqualTo(new ScalingFloors(3, 3, false));
        assertThat(of(new ScalingDemand(1, 5_001))).isEqualTo(new ScalingFloors(3, 3, false));
    }

    /** surge 를 넣지 않은 채 평소 대수만 올린 환경도 뜨고, 회차가 열려도 평소 아래로 내리지 않는다. */
    @Test
    void 대기열_평소_대수가_surge_보다_크면_평소_대수() {
        ScalingProperties properties = new ScalingProperties(false, Duration.ofMinutes(1), "ap-northeast-2", "",
                Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(1),
                new ScalingProperties.Waitingroom("nova-prod-waitingroom", 4, 10, 5000, 3),
                new ScalingProperties.Preorder("nova-prod-preorder", 1, 5, 3));

        assertThat(ScalingFloors.of(new ScalingDemand(1, null), OptionalLong.empty(), ACCEPT_PER_SECOND, properties))
                .isEqualTo(new ScalingFloors(3, 4, false));
        assertThat(ScalingFloors.of(new ScalingDemand(1, 5_001), OptionalLong.empty(), ACCEPT_PER_SECOND, properties))
                .isEqualTo(new ScalingFloors(3, 4, false));
    }

    @Test
    void 최대_대수를_넘으면_최대로_자르고_알린다() {
        assertThat(of(new ScalingDemand(1, 1_000_000)))
                .isEqualTo(new ScalingFloors(3, 10, true));
    }

    @Test
    void 회차가_없으면_관리자_입장_속도가_커도_평소_대수() {
        assertThat(ScalingFloors.of(new ScalingDemand(0, null), OptionalLong.of(100_000), ACCEPT_PER_SECOND, PROPERTIES))
                .isEqualTo(new ScalingFloors(1, 1, false));
    }

    /** 입장 속도는 살아 있는 preorder 용량의 합에 묶이므로, 관리자 값을 받을 대수(올림)까지 올린다. */
    @Test
    void 회차가_있으면_관리자_입장_속도를_받을_대수와_surge_중_큰_값() {
        assertThat(of(new ScalingDemand(1, null), 250)).isEqualTo(new ScalingFloors(3, 3, false));
        assertThat(of(new ScalingDemand(1, null), 401)).isEqualTo(new ScalingFloors(5, 3, false));
        assertThat(of(new ScalingDemand(1, null), 0)).isEqualTo(new ScalingFloors(3, 3, false));
    }

    @Test
    void 관리자_입장_속도가_최대_대수를_넘으면_최대로_자르고_알린다() {
        assertThat(of(new ScalingDemand(1, null), 1_000)).isEqualTo(new ScalingFloors(5, 3, true));
        assertThat(of(new ScalingDemand(1, null), Long.MAX_VALUE)).isEqualTo(new ScalingFloors(5, 3, true));
    }

    /** 클러스터 · 서비스 이름은 환경마다 달라(운영은 nova-prod-*) 기본값이 없다. */
    @Test
    void 켜려면_클러스터와_서비스_이름을_넣어야_한다() {
        assertThatThrownBy(() -> properties(true, " ", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScalingProperties(true, Duration.ofMinutes(1), "ap-northeast-2", "nova-prod",
                Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(1),
                new ScalingProperties.Waitingroom("", 1, 10, 5000, 3),
                new ScalingProperties.Preorder("nova-prod-preorder", 1, 5, 3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(properties(true, "nova-prod", 10).enabled()).isTrue();
    }

    @Test
    void 고정_대수는_평소_이상_최대_이하() {
        assertThatThrownBy(() -> new ScalingProperties.Preorder("preorder", 2, 5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScalingProperties.Waitingroom("waitingroom", 3, 2, 5000, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScalingProperties.Waitingroom("waitingroom", 0, 5, 5000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ScalingFloors of(ScalingDemand demand) {
        return ScalingFloors.of(demand, OptionalLong.empty(), ACCEPT_PER_SECOND, PROPERTIES);
    }

    private static ScalingFloors of(ScalingDemand demand, long admissionCredit) {
        return ScalingFloors.of(demand, OptionalLong.of(admissionCredit), ACCEPT_PER_SECOND, PROPERTIES);
    }

    private static ScalingProperties properties(boolean enabled, String cluster, int waitingroomMax) {
        return new ScalingProperties(enabled, Duration.ofMinutes(1), "ap-northeast-2", cluster, Duration.ofMinutes(15),
                Duration.ofMinutes(30), Duration.ofMinutes(1),
                new ScalingProperties.Waitingroom("nova-prod-waitingroom", 1, waitingroomMax, 5000, 3),
                new ScalingProperties.Preorder("nova-prod-preorder", 1, 5, 3));
    }
}
