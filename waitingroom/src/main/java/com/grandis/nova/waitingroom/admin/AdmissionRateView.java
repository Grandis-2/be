package com.grandis.nova.waitingroom.admin;

import java.util.List;

/**
 * 운영값과 출처. value 가 null 이면 제한 없음이다. 리더가 다음 회차(1초 안)에 읽어 적용한다.
 *
 * @param products 판정 재료에 있는 모델과 상한을 정한 모델. queueLimit 은 줄 최대 길이(최대 대기 시간 × 입장 속도)
 */
public record AdmissionRateView(Setting globalCredit, Setting maxWaitSeconds, List<ProductRate> products) {

    public enum Source { DEFAULT, OPERATIONAL }

    public record Setting(Long value, Source source) {
    }

    public record ProductRate(String productId, Setting cap, Long queueLimit) {
    }
}
