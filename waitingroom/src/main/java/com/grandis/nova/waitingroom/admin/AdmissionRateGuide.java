package com.grandis.nova.waitingroom.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.waitingroom.WaitingroomErrorCode;
import com.grandis.nova.waitingroom.admin.AdmissionRateView.CapacityGuide;
import com.grandis.nova.waitingroom.control.CapacityStatus;
import com.grandis.nova.waitingroom.control.GatewaySnapshot;

import java.util.Map;
import java.util.Optional;

/**
 * 전역 입장 속도를 "지금 반영되는가" 로 나눈다. 실제 속도는 min(운영값, preorder 용량 합) 이라 높게 정해도 서버를 넘지 않는다.
 * 용량 합(권장 상한)까지는 바로 반영, preorder 최대 대수로 닿는 속도(위험선)까지는 증설 뒤 반영, 그 위는 닿지 않는다.
 * 용량을 모르면(표시 없음 · 재료가 낡음) 구간을 나누지 않는다.
 */
final class AdmissionRateGuide {

    private AdmissionRateGuide() {
    }

    static CapacityGuide of(Optional<GatewaySnapshot> snapshot, long operationalCredit, long maxGlobalCredit,
                            int preorderMaxTasks) {
        Optional<CapacityStatus> capacity = snapshot.map(GatewaySnapshot::capacity).filter(CapacityStatus::known);
        Long recommended = capacity.map(CapacityStatus::sum).orElse(null);
        Long danger = capacity.map(found -> dangerLine(found, maxGlobalCredit, preorderMaxTasks)).orElse(null);
        return new CapacityGuide(recommended, danger,
                snapshot.map(current -> current.meta().globalCredit()).orElse(null),
                capacity.map(CapacityStatus::ready).orElse(null),
                capacity.map(CapacityStatus::warming).orElse(null),
                snapshot.map(GatewaySnapshot::brakeFactor).orElse(null),
                snapshot.flatMap(GatewaySnapshot::relayWindow).filter(relay -> relay.relayed() > 0)
                        .map(relay -> (double) relay.bad() / relay.relayed()).orElse(null),
                recommended != null && operationalCredit > recommended);
    }

    /** 늘 수 있는 만큼 늘었을 때의 용량. 지금 합보다 작지 않고, 운영 최댓값을 넘지 않는다. */
    private static long dangerLine(CapacityStatus capacity, long maxGlobalCredit, int preorderMaxTasks) {
        long reachable = capacity.perTask() > maxGlobalCredit / preorderMaxTasks ? maxGlobalCredit
                : capacity.perTask() * preorderMaxTasks;
        return Math.min(maxGlobalCredit, Math.max(capacity.sum(), reachable));
    }

    /** 줄이는 변경은 언제나 받는다. 늘릴 때만 구간을 본다. */
    static void check(long requested, long current, boolean confirmed, CapacityGuide guide) {
        if (requested <= current || guide.recommendedMax() == null || requested <= guide.recommendedMax()) {
            return;
        }
        Map<String, Object> details = Map.of("recommendedMax", guide.recommendedMax(), "dangerLine", guide.dangerLine());
        if (requested > guide.dangerLine()) {
            throw new BusinessException(WaitingroomErrorCode.ADMISSION_RATE_UNREACHABLE, details);
        }
        if (!confirmed) {
            throw new BusinessException(WaitingroomErrorCode.ADMISSION_RATE_NEEDS_CONFIRM, details);
        }
    }
}
