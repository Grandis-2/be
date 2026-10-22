package com.grandis.nova.waitingroom.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.waitingroom.WaitingroomErrorCode;
import com.grandis.nova.waitingroom.admin.AdmissionRateView.CapacityGuide;
import com.grandis.nova.waitingroom.control.CapacityStatus;
import com.grandis.nova.waitingroom.control.GatewaySnapshot;
import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionRateGuideTest {

    private static Optional<GatewaySnapshot> snapshot(CapacityStatus capacity) {
        return Optional.of(new GatewaySnapshot(Map.of(), new SnapshotMeta(100, 1, MaxWait.unlimited()), 0, 1.0, capacity));
    }

    private static CapacityGuide guide(CapacityStatus capacity, long maxGlobalCredit, int maxTasks) {
        return AdmissionRateGuide.of(snapshot(capacity), capacity.operationalCredit(), maxGlobalCredit, maxTasks);
    }

    @Test
    void 위험선은_최대_대수_곱하기_태스크당_용량이고_지금_합보다_작지_않으며_운영_최댓값에서_자른다() {
        assertThat(guide(new CapacityStatus(100, 150, 2, 1, 100), 1_500, 5).dangerLine()).isEqualTo(500);
        assertThat(guide(new CapacityStatus(100, 700, 7, 0, 100), 1_500, 5).dangerLine())
                .as("설정보다 많이 떠 있다").isEqualTo(700);
        assertThat(guide(new CapacityStatus(100, 150, 2, 0, 1_000), 1_500, 5).dangerLine()).isEqualTo(1_500);
        assertThat(guide(new CapacityStatus(100, 150, 2, 0, Long.MAX_VALUE), Long.MAX_VALUE, 5).dangerLine())
                .as("넘치지 않는다").isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 용량을_모르거나_재료가_없으면_구간을_나누지_않는다() {
        CapacityGuide unknown = guide(CapacityStatus.unknown(100), 1_500, 5);
        assertThat(unknown.recommendedMax()).isNull();
        assertThat(unknown.scalingPending()).isFalse();
        assertThatCode(() -> AdmissionRateGuide.check(1_500, 100, false, unknown)).doesNotThrowAnyException();

        CapacityGuide none = AdmissionRateGuide.of(Optional.empty(), 100, 1_500, 5);
        assertThat(none.appliedCredit()).isNull();
        assertThat(none.brakeFactor()).isNull();
    }

    @Test
    void 늘릴_때만_구간을_보고_줄이는_변경은_받는다() {
        CapacityGuide guide = guide(new CapacityStatus(100, 150, 2, 0, 100), 1_500, 5);

        assertThatCode(() -> AdmissionRateGuide.check(150, 100, false, guide)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AdmissionRateGuide.check(151, 100, false, guide)).isInstanceOfSatisfying(
                BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(WaitingroomErrorCode.ADMISSION_RATE_NEEDS_CONFIRM));
        assertThatCode(() -> AdmissionRateGuide.check(500, 100, true, guide)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AdmissionRateGuide.check(501, 100, true, guide)).isInstanceOfSatisfying(
                BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(WaitingroomErrorCode.ADMISSION_RATE_UNREACHABLE));
        assertThatCode(() -> AdmissionRateGuide.check(900, 1_000, false, guide)).as("위험선 위에서 줄이기")
                .doesNotThrowAnyException();
    }
}
