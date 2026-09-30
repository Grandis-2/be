package com.grandis.nova.catalog.registration;

import java.util.List;

/**
 * 등록 ② 의 preorder 쪽 호출. 트랜잭션 밖에서, 상한 시간을 두고 부르고 결과는 등록 기록의 단계 시각으로 남긴다.
 * 구현(HTTP 클라이언트 · "이미 됐는지 확인" · 리스 획득과 연장)은 등록 조율 티켓의 몫이다. 이 티켓은 계약과 스텁만 둔다.
 */
public interface PreorderRegistrationPort {

    void putCampaign(Long productId, RegistrationPlan.Campaign campaign);

    void putShipmentBatches(Long productId, List<RegistrationPlan.ShipmentBatch> batches);
}
