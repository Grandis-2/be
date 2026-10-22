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

    private static CapacityGuide guide(CapacityStatus capacity) {
        return AdmissionRateGuide.of(snapshot(capacity), capacity.operationalCredit());
    }

    @Test
    void 위험선은_증설로_닿는_용량이고_지금_합보다_작지_않다() {
        assertThat(guide(new CapacityStatus(100, 150, 2, 1, 500, false)).dangerLine()).isEqualTo(500);
        assertThat(guide(new CapacityStatus(100, 700, 7, 0, 600, false)).dangerLine()).as("예열이 끝난 합이 더 크다")
                .isEqualTo(700);
    }

    @Test
    void 용량을_모르거나_재료가_없으면_구간을_나누지_않는다() {
        CapacityGuide unknown = guide(CapacityStatus.unknown(100));
        assertThat(unknown.recommendedMax()).isNull();
        assertThat(unknown.scalingPending()).isFalse();
        assertThatCode(() -> AdmissionRateGuide.check(1_500, 100, false, unknown)).doesNotThrowAnyException();

        assertThat(guide(new CapacityStatus(100, 150, 2, 0, 500, true)).recommendedMax()).as("직전 값 유지").isNull();

        CapacityGuide none = AdmissionRateGuide.of(Optional.empty(), 100);
        assertThat(none.appliedCredit()).isNull();
        assertThat(none.brakeFactor()).isNull();
    }

    @Test
    void 늘릴_때만_구간을_보고_줄이는_변경은_받는다() {
        CapacityGuide guide = guide(new CapacityStatus(100, 150, 2, 0, 500, false));

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
