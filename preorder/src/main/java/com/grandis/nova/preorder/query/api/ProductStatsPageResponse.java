package com.grandis.nova.preorder.query.api;

import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.query.api.PreorderStatsResponse.DeadLetters;
import com.grandis.nova.preorder.query.application.ProductStats;
import com.grandis.nova.preorder.query.application.ProductStatsPage;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 상품별 운영 현황 한 페이지. 항목은 상품 하나의 현황({@link PreorderStatsResponse})과 같은 칸에 회차 일정이 붙는다.
 *
 * @param nextCursor 다음 페이지 커서. 마지막 페이지면 null
 * @param countedAt  센 시각. 이미 받은 것보다 이전이면 늦게 온 응답이다
 */
public record ProductStatsPageResponse(List<Item> items, String nextCursor, Instant countedAt) {

    /**
     * @param opensAt  회차 접수 시작
     * @param closesAt 회차 마감
     */
    public record Item(
            UUID productId,
            Instant opensAt,
            Instant closesAt,
            Map<PreorderStatus, Long> preorders,
            Map<SyncJobType, Map<SyncJobStatus, Long>> syncJobs,
            DeadLetters deadLetters,
            long paymentOverdue,
            Instant oldestPendingSyncAt,
            Instant oldestInFlightSyncAt
    ) {

        static Item from(ProductStats stats) {
            return new Item(stats.campaign().productId(), stats.campaign().opensAt(), stats.campaign().closesAt(),
                    stats.preorders().byStatus(), stats.syncJobs(),
                    new DeadLetters(stats.deadLetters().count(), stats.deadLetters().oldestAt()),
                    stats.preorders().paymentOverdue(), stats.preorders().oldestPendingSyncAt(),
                    stats.preorders().oldestInFlightSyncAt());
        }
    }

    public static ProductStatsPageResponse from(ProductStatsPage page) {
        return new ProductStatsPageResponse(page.items().stream().map(Item::from).toList(), page.nextCursor(),
                page.countedAt());
    }
}
