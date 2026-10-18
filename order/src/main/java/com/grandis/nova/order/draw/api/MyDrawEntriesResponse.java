package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.draw.DrawEntryService;
import com.grandis.nova.order.draw.domain.model.MyDrawEntry;
import com.grandis.nova.order.draw.domain.model.MyDrawEntryState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 내 응모 목록(GET /api/v1/draws/entries/me). 본인에게만 보인다. 페이지 칸(items · page · size · total)은 회차 목록과 같고, 탭 건수를 더한다.
 *
 * @param total  고른 탭(filter)의 건수
 * @param counts 탭 건수 — 고른 탭과 상관없이 둘 다
 */
public record MyDrawEntriesResponse(List<Item> items, int page, int size, long total, Counts counts) {

    static MyDrawEntriesResponse of(DrawEntryService.MyEntries mine, Instant now) {
        var page = mine.entries();
        return new MyDrawEntriesResponse(page.items().stream().map(entry -> Item.of(entry, mine, now)).toList(), page.page(), page.size(),
                page.total(), new Counts(mine.counts().all(), mine.counts().won()));
    }

    /**
     * @param state      AWAITING_PAYMENT(결제 대기 · 응모 기간 안) · PAYMENT_CONFIRMING(결제 확인 중 — 마감 뒤에도) · AWAITING_RESULT(결제 완료 ·
     *                   결과 전) · WON · LOST · CLOSED_UNPAID(결제 대기인 채로 마감) — 응모 상태 · 회차 마감 · 결과 반영을 now 로 합친 것
     * @param enteredAt  응모한 시각
     * @param wonOrderId 당첨이면 증정 0원 주문(내 주문 목록의 source DRAW), 아니면 null
     * @param draw       그 회차(회차 상세와 같은 모양) — 증정품 · 응모비 · 마감 시각
     */
    public record Item(UUID entryId, MyDrawEntryState state, Instant enteredAt, UUID wonOrderId, DrawResponse draw) {

        static Item of(MyDrawEntry entry, DrawEntryService.MyEntries mine, Instant now) {
            var campaign = mine.campaigns().get(entry.campaignId());
            return new Item(entry.entryId(), entry.stateAt(campaign.closesAt(), now), entry.createdAt(), entry.wonOrderId(),
                    DrawResponse.of(campaign, now));
        }
    }

    /** @param all 전체 탭 · @param won 당첨 탭 */
    public record Counts(long all, long won) {
    }
}
