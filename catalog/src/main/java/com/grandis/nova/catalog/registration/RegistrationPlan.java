package com.grandis.nova.catalog.registration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ①(catalog 저장)이 끝난 뒤 ②(다른 서비스 호출)에 넘길 계획. ① 에서 요청으로 만들어 등록 기록(planPayload)에 JSON 으로 고정하고,
 * 재개(IN_PROGRESS)는 그 저장본을 쓴다 — 같은 키로 다시 온 본문으로 다시 만들지 않는다(2026-10-01 결정).
 * 저장본은 엄격하게 읽는다 — 모르는 칸이나 빠진 칸이 있으면 읽지 않고 그 등록을 PLAN_UNAVAILABLE 로 막는다(시험으로 확인).
 * 그래서 칸 이름 · 모양을 바꾸면 그 전에 저장한 미완료 등록은 재개되지 않는다. 회차 시각은 마이크로초로 잘라 고정한다.
 *
 * @param stockByOptionId 일반 상품의 옵션별 초기 재고(order PUT stock). 사전예약은 비어 있다
 * @param campaign        사전예약 회차(preorder PUT preorder-campaign). 일반은 null
 * @param shipmentBatches 사전예약 배송 차수(preorder PUT shipment-batches). 일반은 비어 있다. 요청 DTO 와 분리된 모양이라 포트 구현이 요청을 모른다
 */
public record RegistrationPlan(
        Long productId,
        Map<Long, Integer> stockByOptionId,
        Campaign campaign,
        List<ShipmentBatch> shipmentBatches
) {

    public record Campaign(Instant opensAt, Instant closesAt) {
    }

    public record ShipmentBatch(int batchNumber, long positionFrom, Long positionTo, LocalDate estimatedShipStart,
                                LocalDate estimatedShipEnd) {
    }
}
