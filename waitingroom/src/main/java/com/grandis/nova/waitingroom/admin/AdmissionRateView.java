package com.grandis.nova.waitingroom.admin;

import java.util.List;

/**
 * 운영값과 출처. value 가 null 이면 제한 없음이다. 리더가 다음 회차(1초 안)에 읽어 적용한다.
 *
 * @param maxGlobalCredit   전역 속도와 모델별 상한으로 받는 최댓값(배포 설정)
 * @param leaveGraceSeconds 조회가 끊긴 대기자를 줄에 남겨 두는 시간
 * @param products          판정 재료에 있는 모델과 상한을 정한 모델. queueLimit 은 줄 최대 길이(최대 대기 시간 × 입장 속도)
 */
public record AdmissionRateView(Setting globalCredit, long maxGlobalCredit, Setting maxWaitSeconds,
                                Setting leaveGraceSeconds, List<ProductRate> products) {

    public enum Source { DEFAULT, OPERATIONAL }

    public record Setting(Long value, Source source) {
    }

    public record ProductRate(String productId, Setting cap, Long queueLimit) {
    }
}
