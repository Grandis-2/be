package com.grandis.nova.catalog.integration;

import java.util.List;

/** catalog 가 부르는 내부 API 그룹 이름. 주소 · 타임아웃은 spring.http.serviceclient.{이름} 설정으로 준다. */
public final class Dependencies {

    public static final String ORDER = "order";
    public static final String MEMBER = "member";
    /**
     * order 의 재고 조회(GET /internal/stocks) — 토큰을 싣지 않는 그룹. 주소 · 타임아웃 기본값은 order 와 같다(CatalogDefaults). 토큰 릴레이 대상
     * ({@link #ALL})에 넣지 않는다.
     */
    public static final String ORDER_STOCKS = "order-stocks";
    /** 토큰 릴레이 그룹 — 받는 쪽의 401 을 사용자 토큰 문제로 보는 것도 이 그룹뿐이다(InternalCalls). */
    public static final List<String> ALL = List.of(ORDER, MEMBER);

    private Dependencies() {
    }
}
