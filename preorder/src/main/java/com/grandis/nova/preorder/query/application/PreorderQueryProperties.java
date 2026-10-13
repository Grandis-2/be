package com.grandis.nova.preorder.query.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param activePageSize 진행 중인 예약 조회의 한 페이지 크기. 진행 중인 예약은 상품마다 한 건이라
 *                       사전 예약 상품 수를 넘지 않는다 — 200 이면 보통 한 번에 다 담기고, 넘치면 커서로 잇는다
 */
@ConfigurationProperties("nova.preorder-query")
record PreorderQueryProperties(
        @DefaultValue("200") int activePageSize
) {
    PreorderQueryProperties {
        if (activePageSize < 1) {
            throw new IllegalArgumentException("nova.preorder-query.active-page-size 는 1 이상이어야 한다");
        }
    }
}
