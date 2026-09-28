package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.detail.ProductDetailView;

/**
 * 같은 Idempotency-Key 로 온 요청의 결과 갈래.
 * CREATED — 새로 저장했다(①). REPLAYED — 이미 완료된 등록의 결과를 그대로 돌려준다. IN_PROGRESS — 미완료 등록이 있어 재개 대상이다.
 *
 * @param plan    CREATED 일 때만. ②에 넘길 계획
 * @param preview CREATED 일 때만. 관리자 미리보기 — 등록과 같은 트랜잭션에서 읽어 registration 과 한 스냅샷이다
 */
public record RegistrationOutcome(Kind kind, RegistrationStatusView registration, RegistrationPlan plan, ProductDetailView preview) {

    public enum Kind { CREATED, REPLAYED, IN_PROGRESS }
}
