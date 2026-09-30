package com.grandis.nova.catalog.registration;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ② 의 스텁. 아직 아무도 부르지 않는다 — 등록은 ① 에서 멈춘다. 등록 조율 티켓이 HTTP 구현으로 바꾼다.
 * 실수로 불리면 조용히 성공하는 대신 예외를 내 "② 가 연결된 것처럼 보이는" 상태를 막는다.
 */
@Component
public class NotYetWiredRegistrationPorts implements PreorderRegistrationPort, StockRegistrationPort {

    @Override
    public void putCampaign(Long productId, RegistrationPlan.Campaign campaign) {
        throw notWired();
    }

    @Override
    public void putShipmentBatches(Long productId, List<RegistrationPlan.ShipmentBatch> batches) {
        throw notWired();
    }

    @Override
    public void putStock(Long productId, Map<Long, Integer> stockByOptionId) {
        throw notWired();
    }

    private static UnsupportedOperationException notWired() {
        return new UnsupportedOperationException("등록 ② 는 아직 연결되지 않았다(등록 조율 티켓).");
    }
}
