package com.grandis.nova.order.draw;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawProgress;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.draw.domain.repository.DrawEntryStore;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
import com.grandis.nova.order.outbox.DrawRequested;
import com.grandis.nova.order.stock.StockLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 회차의 추첨 요청과 결과 반영. 회차마다 한 번씩이다 — 회차 행을 잠그고 요청 · 반영 표식(전제를 건 UPDATE)으로 가른다.
 *
 * 추첨할 차례: 마감 뒤 {@link #CLOSE_GRACE} 가 지났고 결제 확인 중(AUTHORIZING) 응모가 없다. 마감 뒤에는 결제가 새로 시작되지 않으므로
 * (DrawEntryPaymentService — 승인 직전에 기간을 다시 본다) 이때의 결제 완료 응모가 추첨 대상 전부다 — 단, 인스턴스 사이 시계 차가 여유보다
 * 작을 때 · payment 가 되돌린 결제창을 나중에 승인하지 않을 때 · 수동 수정이 없을 때다. 그 밖으로 추첨이 정해진 뒤 결제 완료가 되면
 * 결과 반영 쪽이 "드로우 추첨 뒤 결제 완료" ERROR 를 남긴다(DrawEntryPaymentResults — 수동 환불 대상).
 * - 결제 완료 응모가 당첨 인원 이하면 추첨 없이 전원 당첨이다(2026-10-11 결정) — 바로 반영한다.
 * - 많으면 DRAW_REQUESTED 로 Mock 에 추첨을 맡기고(worker), DRAW_RESULT_READY 로 받은 당첨 응모를 반영한다.
 *
 * 반영(한 트랜잭션): 당첨 응모마다 응모 때 배송지로 0원 주문(결제 확인 대기로 생긴다 — 이 뒤로 일반 배송 단계), 당첨분 재고를 확보 → 판매로,
 * 남은 확보(당첨 인원 − 당첨 수 — 전원 당첨일 때만 생긴다, Mock 결과는 늘 당첨 인원만큼이다)는 반납. 판매로 옮기지 못하면 "재고 어긋남" ERROR 를
 * 남기고, 반납할 확보가 모자라면(장부 어긋남) 반영 전체를 롤백한다 — 전원 당첨에서 판매가 모자랐다면 반납도 모자라 결국 롤백된다.
 */
@Service
public class DrawSettlement {

    private static final Logger log = LoggerFactory.getLogger(DrawSettlement.class);

    static final String WON_REASON = "DRAW_WON";
    /** 마감 뒤 이만큼 기다린 뒤 추첨할 차례로 본다 — order 인스턴스 사이 시계 차(NTP)보다 넉넉하게. */
    static final Duration CLOSE_GRACE = Duration.ofSeconds(30);

    private final DrawCampaignStore campaigns;
    private final DrawEntryStore entries;
    private final OrderLedger orders;
    private final StockLedger stock;
    private final OutboxWriter outbox;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    public DrawSettlement(DrawCampaignStore campaigns, DrawEntryStore entries, OrderLedger orders, StockLedger stock, OutboxWriter outbox,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.campaigns = campaigns;
        this.entries = entries;
        this.orders = orders;
        this.stock = stock;
        this.outbox = outbox;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 추첨할 차례인 회차 하나를 처리한다 — 전원 당첨이면 반영, 아니면 추첨 요청. @return 이번에 무언가 했으면 true */
    public boolean close(UUID campaignId) {
        return Boolean.TRUE.equals(writeTransaction.execute(status -> {
            Instant now = clock.instant();
            campaigns.lock(campaignId);
            DrawCampaign campaign = campaigns.findById(campaignId).orElseThrow(() -> new IllegalStateException("회차가 없다: " + campaignId));
            DrawProgress progress = campaigns.findProgress(campaignId).orElseThrow();
            if (progress.drawRequestedAt() != null || progress.settledAt() != null || progress.authorizingEntries() > 0
                    || now.isBefore(campaign.closesAt().plus(CLOSE_GRACE))) {
                return false;
            }
            long paid = entries.countPaid(campaignId);
            if (paid <= campaign.winnerCount()) {
                List<DrawEntry> winners = entries.findPaid(campaignId);
                apply(campaign, winners, now);
                log.info("드로우 전원 당첨 반영 drawId={} winners={} winnerCount={}", campaignId, winners.size(), campaign.winnerCount());
                return true;
            }
            if (!campaigns.markDrawRequested(campaignId, now)) {
                throw new IllegalStateException("잠근 회차의 추첨 요청 표식이 이미 있다: drawId=" + campaignId);
            }
            outbox.append(new DrawRequested(campaignId, paid, campaign.winnerCount()));
            log.info("드로우 추첨 요청 drawId={} paidEntries={} winnerCount={}", campaignId, paid, campaign.winnerCount());
            return true;
        }));
    }

    /**
     * Mock 의 추첨 결과(DRAW_RESULT_READY). 이미 반영했으면 아무것도 하지 않는다(다시 받기).
     *
     * @throws IllegalArgumentException 요청하지 않은 회차 · 당첨 수가 당첨 인원과 다름 · 같은 응모가 두 번 · 그 회차의 결제 완료 응모가 아님 —
     *                                  계약 위반이라 다시 받아도 같다(DLQ)
     */
    public void settle(UUID campaignId, List<UUID> winnerEntryIds) {
        writeTransaction.executeWithoutResult(status -> {
            campaigns.lock(campaignId);
            DrawCampaign campaign = campaigns.findById(campaignId).orElseThrow(() -> new IllegalArgumentException("회차가 없다: " + campaignId));
            DrawProgress progress = campaigns.findProgress(campaignId).orElseThrow();
            if (progress.settledAt() != null) {
                if (new HashSet<>(entries.findWinnerIds(campaignId)).equals(new HashSet<>(winnerEntryIds))) {
                    log.info("이미 반영한 드로우 결과 — 다시 받기 drawId={}", campaignId);
                } else {
                    log.error("이미 반영한 드로우에 다른 당첨 목록이 왔다 — 반영하지 않음, Mock · worker 확인 drawId={} received={}",
                            campaignId, winnerEntryIds);
                }
                return;
            }
            if (progress.drawRequestedAt() == null) {
                throw new IllegalArgumentException("추첨을 요청하지 않은 회차의 결과다: drawId=" + campaignId);
            }
            if (winnerEntryIds.size() != campaign.winnerCount() || new HashSet<>(winnerEntryIds).size() != winnerEntryIds.size()) {
                throw new IllegalArgumentException("당첨 수가 당첨 인원과 다르거나 같은 응모가 두 번이다: drawId=%s, winners=%d, winnerCount=%d"
                        .formatted(campaignId, winnerEntryIds.size(), campaign.winnerCount()));
            }
            Map<UUID, DrawEntry> paid = entries.findPaidAmong(campaignId, winnerEntryIds).stream()
                    .collect(Collectors.toMap(DrawEntry::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
            List<DrawEntry> winners = winnerEntryIds.stream().map(id -> {
                DrawEntry entry = paid.get(id);
                if (entry == null) {
                    throw new IllegalArgumentException("그 회차의 결제 완료 응모가 아니다: drawId=" + campaignId + ", entryId=" + id);
                }
                return entry;
            }).toList();
            apply(campaign, winners, clock.instant());
            log.info("드로우 결과 반영 drawId={} winners={}", campaignId, winners.size());
        });
    }

    /** 반영 — 회차를 잠근 트랜잭션 안에서 부른다. */
    private void apply(DrawCampaign campaign, List<DrawEntry> winners, Instant now) {
        if (!campaigns.markSettled(campaign.id(), now)) {
            throw new IllegalStateException("잠근 회차의 반영 표식이 이미 있다: drawId=" + campaign.id());
        }
        for (DrawEntry winner : winners) {
            Order order = orders.place(new OrderDraft(winner.customerId(), OrderSource.DRAW, null, null, winner.shipTo(),
                    List.of(OrderLine.withoutWarranty(campaign.productId(), campaign.optionId(), Quantity.ONE, Money.ZERO,
                            campaign.productTitle(), campaign.optionTitle()))), EventCause.system(WON_REASON));
            if (!entries.markWon(campaign.id(), winner.id(), order.id(), now)) {
                throw new IllegalStateException("당첨 주문을 잇지 못했다: drawId=" + campaign.id() + ", entryId=" + winner.id());
            }
        }
        if (!winners.isEmpty()) {
            List<UUID> mismatched = stock.sell(Map.of(campaign.optionId(), winners.size()));
            if (!mismatched.isEmpty()) {
                log.error("재고 어긋남 — 드로우 당첨분을 판매로 옮기지 못했다(당첨 주문은 만든다) drawId={} optionId={} quantity={}",
                        campaign.id(), campaign.optionId(), winners.size());
            }
        }
        int leftover = campaign.winnerCount() - winners.size();
        if (leftover > 0) {
            stock.release(Map.of(campaign.optionId(), leftover));
        }
    }
}
