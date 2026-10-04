package com.grandis.nova.preorder.integration;

import java.util.List;

/** 호출하는 다른 서비스의 이름. 장애 대응 설정(resilience4j.*.instances.&lt;이름&gt;)과 호출이 같은 이름을 써야 한다. */
public final class Dependencies {

    public static final String CATALOG = "catalog";
    public static final String ORDER = "order";
    public static final List<String> ALL = List.of(CATALOG, ORDER);

    private Dependencies() {
    }
}
