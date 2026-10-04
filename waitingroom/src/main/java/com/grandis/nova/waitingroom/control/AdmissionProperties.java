package com.grandis.nova.waitingroom.control;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 입장 속도 기본값 — 전 모델 합산 초당 입장 인원, 한산한 모델이 노드 몫에서 줄 없이 통과시킬 비율, 모델별 상한.
 * 운영 중에는 Redis 운영값이 이긴다(관리자 API). preorder 를 늘리면 전역 속도만 운영값으로 올린다.
 */
@ConfigurationProperties("waitingroom.admission")
public record AdmissionProperties(Long globalCredit, Double idleCreditRatio, Long productCap) {

    @ConstructorBinding
    public AdmissionProperties {
        // 부하 시험: preorder 1대 · 회차 하나가 초당 약 200건에서 커넥션 풀이 찬다. 그 75% 로 여유를 둔다
        globalCredit = globalCredit == null ? 150L : globalCredit;
        productCap = productCap == null ? 150L : productCap;
        idleCreditRatio = idleCreditRatio == null ? 0.7 : idleCreditRatio;
        if (globalCredit < 0) {
            throw new IllegalArgumentException("waitingroom.admission.global-credit 은 0 이상이어야 한다: " + globalCredit);
        }
        if (!Double.isFinite(idleCreditRatio) || idleCreditRatio < 0 || idleCreditRatio > 1) {
            throw new IllegalArgumentException("waitingroom.admission.idle-credit-ratio 는 0..1 이어야 한다: " + idleCreditRatio);
        }
        if (productCap < 1) {
            throw new IllegalArgumentException("waitingroom.admission.product-cap 은 1 이상이어야 한다: " + productCap);
        }
    }

    public AdmissionProperties(Long globalCredit, Double idleCreditRatio) {
        this(globalCredit, idleCreditRatio, null);
    }
}
