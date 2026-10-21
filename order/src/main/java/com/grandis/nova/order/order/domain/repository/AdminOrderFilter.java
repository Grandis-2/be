package com.grandis.nova.order.order.domain.repository;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 목록 조건. 값이 없으면 null 이고 그 조건은 걸지 않는다. 조건끼리는 AND 다.
 *
 * @param orderIdPrefix    주문 번호 앞부분(전체 id 포함)
 * @param customerId       회원 정확히 일치
 * @param shipToName       받는 분 이름 일치(콜레이션을 따라 대소문자 · 악센트는 가리지 않는다)
 * @param shipToPhoneLast4 받는 분 전화번호의 숫자 뒤 4자리
 * @param preorderToken    예약 공개 UUID(소문자 문자열) 정확히 일치
 * @param productId        그 상품이 든 주문
 * @param optionId         그 옵션이 든 주문. productId 와 함께면 같은 주문상품이 둘 다 맞아야 한다
 * @param createdFrom      주문 시각 이 시각 이상
 * @param createdTo        주문 시각 이 시각 미만
 */
public record AdminOrderFilter(OrderStatus status, OrderSource source, OrderIdPrefix orderIdPrefix, UUID customerId, String shipToName,
                               String shipToPhoneLast4, String preorderToken, UUID productId, UUID optionId, Instant createdFrom,
                               Instant createdTo) {

    public static final AdminOrderFilter NONE = new AdminOrderFilter(null, null, null, null, null, null, null, null, null, null, null);

    /**
     * 몇 건으로 좁히는 조건이 있는가 — 있으면 그 조건의 색인으로 고른 뒤 정렬한다(최신순 색인을 훑지 않는다). 상태 · 회원은 (상태 | 회원, 만든 시각)
     * 색인이 거르기와 정렬을 함께 해 넣지 않는다. 기간은 최신순 색인 그 자체의 범위다. 출처는 색인이 없어 어느 쪽이든 행을 훑는다 — 최신순 색인을
     * 거꾸로 훑다 한 쪽이 차면 멈추므로 표 전체를 읽고 정렬하던 것보다 나빠지지 않는다.
     */
    public boolean narrowing() {
        return orderIdPrefix != null || shipToName != null || shipToPhoneLast4 != null || preorderToken != null || productId != null
                || optionId != null;
    }
}
