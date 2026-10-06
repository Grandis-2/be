package com.grandis.nova.catalog.integration;

import java.util.List;

/** catalog 가 부르는 내부 API 그룹 이름. 주소 · 타임아웃은 spring.http.serviceclient.{이름} 설정으로 준다. */
public final class Dependencies {

    public static final String ORDER = "order";
    public static final String MEMBER = "member";
    public static final List<String> ALL = List.of(ORDER, MEMBER);

    private Dependencies() {
    }
}
