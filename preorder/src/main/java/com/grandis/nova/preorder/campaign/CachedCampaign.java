package com.grandis.nova.preorder.campaign;

import java.time.Instant;
import java.util.List;

/**
 * 캐시에 두는 회차 — 일정 · 공개 여부 · 판매 상태 · 배송 차수. 회차를 바꾼 트랜잭션이 커밋된 뒤 비우므로 보통은 지금 값이고,
 * 비우기를 놓쳤을 때만 캐시 수명만큼 늦다. 최종 판정(접수 · 오픈 알림)은 회차 행으로 다시 한다.
 * 순번(next_queue_position) · 오픈 알림 표시는 비우지 않고 바뀌므로 넣지 않는다.
 *
 * @param batches 차수 번호 순. 아직 정하지 않았으면 비어 있다
 */
public record CachedCampaign(CampaignSchedule schedule, boolean visible, ProductStatus productStatus,
                             List<ShipmentBatchSnapshot> batches) {

    public CachedCampaign {
        batches = List.copyOf(batches);
    }

    public boolean opened(Instant now) {
        return !now.isBefore(schedule.opensAt());
    }

    public boolean accepting(Instant now) {
        return opened(now) && now.isBefore(schedule.closesAt());
    }

    public CampaignForAccept forAccept() {
        return CampaignForAccept.of(schedule, visible, productStatus);
    }
}
