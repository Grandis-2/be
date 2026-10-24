package com.grandis.nova.batch.reconciliation.run;

import java.util.UUID;

/**
 * 이번 실행에서 찾은 불일치 한 건. 처음 발견 시각은 넣을 때 앞 실행에서 이어받는다.
 *
 * @param productId    불일치 대상의 상품. 알 수 없으면 null
 * @param beSnapshot   검사 때 본 be 값(JSON). be 예약이 없는 건("Mock 에만 있음")은 null
 * @param mockSnapshot 검사 때 본 Mock 원장 행(JSON). be 내부 검사 · Mock 에 없는 건은 null
 */
public record NewFinding(String checkCode, String targetKey, UUID productId, String beSnapshot, String mockSnapshot) {

    public NewFinding {
        if (checkCode == null || checkCode.isBlank() || targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("확인할 것 코드와 대상이 있어야 한다: " + checkCode + " " + targetKey);
        }
        if (beSnapshot == null && mockSnapshot == null) {
            throw new IllegalArgumentException("be · Mock 값이 둘 다 없는 결과는 남기지 않는다: " + checkCode + " " + targetKey);
        }
    }
}
