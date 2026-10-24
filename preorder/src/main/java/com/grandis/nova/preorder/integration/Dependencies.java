package com.grandis.nova.preorder.integration;

import java.util.List;

/** 호출하는 다른 서비스의 이름. 장애 대응 설정(resilience4j.*.instances.&lt;이름&gt;)과 호출이 같은 이름을 써야 한다. */
public final class Dependencies {

    public static final String CATALOG = "catalog";
    public static final String ORDER = "order";
    /** 관리자 예약 목록 · 상세의 회원 이름(MemberNames). 재시도 없이 회로 · 동시 호출 상한만 쓴다. */
    public static final String MEMBER = "member";
    public static final List<String> ALL = List.of(CATALOG, ORDER, MEMBER);

    private Dependencies() {
    }
}
