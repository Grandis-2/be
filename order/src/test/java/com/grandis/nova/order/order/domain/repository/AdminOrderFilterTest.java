package com.grandis.nova.order.order.domain.repository;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 조건을 로그에 찍어도 받는 분 이름 · 전화 뒤 4자리가 나가지 않는다. */
class AdminOrderFilterTest {

    @Test
    void toStringMasksShipToNameAndPhone() {
        UUID customerId = UUID.randomUUID();
        AdminOrderFilter filter = new AdminOrderFilter(null, null, null, customerId, "홍길동", "5678", null, null, null, null, null);

        assertThat(filter.toString()).doesNotContain("홍길동", "5678").contains("shipToName=***", "shipToPhoneLast4=***", customerId.toString());
        assertThat(AdminOrderFilter.NONE.toString()).contains("shipToName=null", "shipToPhoneLast4=null");
    }
}
