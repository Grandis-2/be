package com.grandis.nova.waitingroom.control;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 입장 속도 기본값 — 전 모델 합산 초당 입장 인원, 한산한 모델이 노드 몫에서 줄 없이 통과시킬 비율.
 * 운영 중에는 Redis 운영값이 이긴다(관리자 API). 기본 전역 속도는 부하 시험으로 다시 정한다.
 */
@ConfigurationProperties("waitingroom.admission")
public record AdmissionProperties(Long globalCredit, Double idleCreditRatio) {

    public AdmissionProperties {
        globalCredit = globalCredit == null ? 100L : globalCredit;
        idleCreditRatio = idleCreditRatio == null ? 0.7 : idleCreditRatio;
        if (globalCredit < 0) {
            throw new IllegalArgumentException("waitingroom.admission.global-credit 은 0 이상이어야 한다: " + globalCredit);
        }
        if (!Double.isFinite(idleCreditRatio) || idleCreditRatio < 0 || idleCreditRatio > 1) {
            throw new IllegalArgumentException("waitingroom.admission.idle-credit-ratio 는 0..1 이어야 한다: " + idleCreditRatio);
        }
    }
}
