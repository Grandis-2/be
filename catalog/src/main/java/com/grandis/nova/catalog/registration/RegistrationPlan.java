package com.grandis.nova.catalog.registration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ①(catalog 저장)이 끝난 뒤 ②(다른 서비스 호출)에 넘길 계획. 요청에서 뽑아 메모리에만 둔다 —
 * 재개 때는 클라이언트가 원본 전체를 다시 보내므로 저장하지 않는다. 그래서 재개 갈래(IN_PROGRESS)에서는 계획이 null 이고,
 * 등록 조율 티켓은 재전송된 본문으로 selections → optionId 대응을 다시 만들어야 한다.
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
