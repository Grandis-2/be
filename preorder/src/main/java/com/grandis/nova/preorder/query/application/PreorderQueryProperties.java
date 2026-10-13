package com.grandis.nova.preorder.query.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param activeLimit 진행 중인 예약 조회가 한 번에 주는 최대 건수. 진행 중인 예약은 상품마다 한 건이라
 *                    사전 예약 상품 수를 넘지 않는다 — 200 은 그보다 넉넉한 안전 상한이다
 */
@ConfigurationProperties("nova.preorder-query")
record PreorderQueryProperties(
        @DefaultValue("200") int activeLimit
) {
    PreorderQueryProperties {
        if (activeLimit < 1) {
            throw new IllegalArgumentException("nova.preorder-query.active-limit 은 1 이상이어야 한다");
        }
    }
}
