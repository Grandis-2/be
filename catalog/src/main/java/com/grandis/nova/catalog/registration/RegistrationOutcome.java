package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.detail.ProductDetailView;

/**
 * 같은 Idempotency-Key 로 온 요청의 결과 갈래.
 * CREATED — 새로 저장했다. REPLAYED — 판매 방식별 준비가 끝난 등록을 그대로 돌려준다. IN_PROGRESS — 아직 준비 전이다(등록 이벤트를 처리 중).
 *
 * @param preview CREATED 일 때만. 관리자 미리보기 — 등록과 같은 트랜잭션에서 읽어 registration 과 한 스냅샷이다
 */
public record RegistrationOutcome(Kind kind, RegistrationStatusView registration, ProductDetailView preview) {

    public enum Kind { CREATED, REPLAYED, IN_PROGRESS }
}
