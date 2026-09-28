package com.grandis.nova.catalog.registration;

/**
 * 같은 Idempotency-Key 로 온 요청의 결과 갈래.
 * CREATED — 새로 저장했다(①). REPLAYED — 이미 완료된 등록의 결과를 그대로 돌려준다. IN_PROGRESS — 미완료 등록이 있어 재개 대상이다.
 *
 * @param plan CREATED 일 때만. ②에 넘길 계획
 */
public record RegistrationOutcome(Kind kind, RegistrationStatusView registration, RegistrationPlan plan) {

    public enum Kind { CREATED, REPLAYED, IN_PROGRESS }
}
