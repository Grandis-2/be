package com.grandis.nova.waitingroom.admin;

import java.util.List;

/**
 * 운영값과 출처. value 가 null 이면 제한 없음이다. 리더가 다음 회차(1초 안)에 읽어 적용한다.
 *
 * @param maxGlobalCredit   전역 속도와 모델별 상한으로 받는 최댓값(배포 설정)
 * @param leaveGraceSeconds 조회가 끊긴 대기자를 줄에 남겨 두는 시간
 * @param products          판정 재료에 있는 모델과 상한을 정한 모델. queueLimit 은 줄 최대 길이(최대 대기 시간 × 입장 속도)
 * @param capacity          전역 입장 속도를 정할 때 볼 기준
 */
public record AdmissionRateView(Setting globalCredit, long maxGlobalCredit, Setting maxWaitSeconds,
                                Setting leaveGraceSeconds, List<ProductRate> products, CapacityGuide capacity) {

    public enum Source { DEFAULT, OPERATIONAL }

    public record Setting(Long value, Source source) {
    }

    public record ProductRate(String productId, Setting cap, Long queueLimit) {
    }

    /**
     * 추정 기준이다. 판정 재료가 없거나 preorder 용량을 모르면 해당 값은 null 이다.
     *
     * @param recommendedMax 바로 반영되는 최댓값(지금 preorder 용량 합, 갓 뜬 태스크는 줄여 셈)
     * @param dangerLine     preorder 를 최대 대수로 늘리면 닿는 최댓값. 권장 상한을 넘으면 confirm 이 있어야 저장한다
     * @param appliedCredit  지금 실제로 쓰는 전역 속도(용량 상한 · 브레이크 뒤)
     * @param relayBadRatio  최근 창에서 접수 전달이 나빴던(5xx · 실패 · 느림) 비율. 전달이 없었으면 null
     * @param scalingPending 운영값이 용량 합보다 커서 preorder 가 늘어야 반영되는 중
     */
    public record CapacityGuide(Long recommendedMax, Long dangerLine, Long appliedCredit, Integer readyTasks, Integer warmingTasks, Double brakeFactor, Double relayBadRatio,
                                boolean scalingPending) {
    }
}
