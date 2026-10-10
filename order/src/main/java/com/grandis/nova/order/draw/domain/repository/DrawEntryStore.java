package com.grandis.nova.order.draw.domain.repository;

import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.draw.domain.model.EntryTransition;
import com.grandis.nova.order.draw.domain.model.NewDrawEntry;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 응모 읽기 · 쓰기 포트. 상태 변경은 전제(지금 상태 · 결제창)를 조건으로 건 UPDATE 한 문장이다 — 동기 응답과 결과 이벤트가 겹쳐도 한 번만
 * 반영된다. 반영되지 않았으면 지금 상태를 돌려준다.
 */
public interface DrawEntryStore {

    /**
     * 새 응모(결제 대기). 넣자마자 flush 한다.
     *
     * @throws com.grandis.nova.order.draw.domain.exception.DrawEntryTakenException 그 회차에 그 회원의 응모가 이미 있다
     */
    DrawEntry insert(NewDrawEntry entry);

    Optional<DrawEntry> findById(UUID id);

    Optional<DrawEntry> findByCampaignAndCustomer(UUID campaignId, UUID customerId);

    /** 결제 대기 → 승인 중(그 결제창). */
    EntryTransition requestPayment(UUID entryId, String providerOrderId, Instant now);

    /** 응모 행을 잠그고 지금 상태를 읽는다(SELECT … FOR UPDATE). 트랜잭션 안에서만 — 같은 트랜잭션의 뒤 UPDATE 와 사이에 다른 전이가 끼지 않게. */
    DrawEntryStatus lockStatus(UUID entryId);

    /** 승인 중 → 결제 완료. 결제창을 대조하지 않는다 — payment 는 대상당 성공 결제가 하나라 언제 와도 그 응모의 결제다. */
    EntryTransition approve(UUID entryId, Instant now);

    /**
     * 결제 대기 → 결제 완료. 상태 머신으로는 갈 수 없는 경우다(승인은 승인 중에만 시작되고, 되돌림은 그 결제창의 확정 거절 · 시작 불가 확언뿐) —
     * 버그 · 수동 수정의 신호다. 그래도 그 대상의 유일한 성공 결제라 받는다: 응모에는 취소가 없어, 놓치면 돈을 받고도 추첨에서 빠진다.
     */
    EntryTransition approveAwaiting(UUID entryId, Instant now);

    /** 승인 중 → 결제 대기. 그 결제창의 승인 중일 때만(늦게 온 이전 결제창의 거절이 새 결제창을 되돌리지 않게). */
    EntryTransition revert(UUID entryId, String providerOrderId, Instant now);
}
