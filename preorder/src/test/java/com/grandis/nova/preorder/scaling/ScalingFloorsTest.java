package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.ScalingDemand;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScalingFloorsTest {

    static final ScalingProperties PROPERTIES = properties(false, "", 10);

    @Test
    void 걸린_회차가_없으면_평소_대수() {
        assertThat(ScalingFloors.of(new ScalingDemand(0, null), PROPERTIES)).isEqualTo(new ScalingFloors(1, 1, false));
    }

    /** 겹친 회차 하나가 끝나도 다른 회차 몫을 내리지 않게 회차마다가 아니라 가장 큰 예상 방문자 수로 맞춘다. */
    @Test
    void 대기열은_가장_큰_예상_방문자_수로_preorder_는_고정_대수() {
        assertThat(ScalingFloors.of(new ScalingDemand(3, 20_000), PROPERTIES))
                .isEqualTo(new ScalingFloors(3, 4, false));
        assertThat(ScalingFloors.of(new ScalingDemand(1, 5_001), PROPERTIES)).isEqualTo(new ScalingFloors(3, 2, false));
    }

    @Test
    void 예상_방문자_수를_넣은_회차가_없으면_대기열은_평소대로() {
        assertThat(ScalingFloors.of(new ScalingDemand(2, null), PROPERTIES)).isEqualTo(new ScalingFloors(3, 1, false));
    }

    @Test
    void 최대_대수를_넘으면_최대로_자르고_알린다() {
        assertThat(ScalingFloors.of(new ScalingDemand(1, 1_000_000), PROPERTIES))
                .isEqualTo(new ScalingFloors(3, 10, true));
    }

    /** 클러스터 · 서비스 이름은 환경마다 달라(운영은 nova-prod-*) 기본값이 없다. */
    @Test
    void 켜려면_클러스터와_서비스_이름을_넣어야_한다() {
        assertThatThrownBy(() -> properties(true, " ", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScalingProperties(true, Duration.ofMinutes(1), "ap-northeast-2", "nova-prod",
                Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(1),
                new ScalingProperties.Waitingroom("", 1, 10, 5000),
                new ScalingProperties.Preorder("nova-prod-preorder", 1, 5, 3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(properties(true, "nova-prod", 10).enabled()).isTrue();
    }

    @Test
    void preorder_의_고정_대수는_평소_이상_최대_이하() {
        assertThatThrownBy(() -> new ScalingProperties.Preorder("preorder", 2, 5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScalingProperties.Waitingroom("waitingroom", 3, 2, 5000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ScalingProperties properties(boolean enabled, String cluster, int waitingroomMax) {
        return new ScalingProperties(enabled, Duration.ofMinutes(1), "ap-northeast-2", cluster, Duration.ofMinutes(15),
                Duration.ofMinutes(30), Duration.ofMinutes(1),
                new ScalingProperties.Waitingroom("nova-prod-waitingroom", 1, waitingroomMax, 5000),
                new ScalingProperties.Preorder("nova-prod-preorder", 1, 5, 3));
    }
}
