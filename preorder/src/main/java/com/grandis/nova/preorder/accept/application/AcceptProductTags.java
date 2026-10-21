package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.preorder.campaign.Campaigns;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 접수 건수 지표의 상품 라벨. 접수 중인 회차만 상품 id 로 내고 나머지는 other 로 묶는다 — 상품 시계열 수는 동시에 접수 중인 회차 수다.
 * 접수를 벗어난 상품의 시계열은 한 번 더 갱신한 뒤 지운다(마지막 증가분이 수집되게). 응답 시간 지표에는 붙이지 않는다(히스토그램이라 무겁다).
 */
@Component
class AcceptProductTags {

    static final String PRODUCT_TAG = "productId";
    static final String OTHER = "other";

    private final Campaigns campaigns;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private volatile Set<UUID> accepting = Set.of();
    /** 지난 갱신에서 접수를 벗어난 상품. 갱신 스레드만 쓴다. */
    private Set<UUID> retiring = Set.of();

    AcceptProductTags(Campaigns campaigns, MeterRegistry meterRegistry, Clock clock) {
        this.campaigns = campaigns;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    String of(UUID productId) {
        return accepting.contains(productId) ? productId.toString() : OTHER;
    }

    /** 기동 직후에도 센다 — 증설된 태스크가 첫 주기 동안 모두 other 로 내지 않게. */
    @Scheduled(fixedDelayString = "${nova.metrics.refresh-interval:30s}", initialDelay = 0)
    void refresh() {
        Set<UUID> now = campaigns.findAcceptingProductIds(clock.instant());
        Set<UUID> previous = accepting;
        // 새로 접수를 시작한 상품은 0 부터 낸다 — 첫 증가가 시계열의 시작이 되면 rate 가 그 한 건을 놓친다
        now.stream().filter(productId -> !previous.contains(productId))
                .forEach(productId -> PreorderAcceptService.registerCreated(meterRegistry, productId.toString()));
        accepting = now;
        retiring.stream().filter(productId -> !now.contains(productId)).forEach(this::remove);
        Set<UUID> left = new HashSet<>(previous);
        left.removeAll(now);
        retiring = left;
    }

    private void remove(UUID productId) {
        for (String metric : List.of(PreorderAcceptService.CREATED_METRIC, AcceptBulkhead.REJECTED_METRIC)) {
            meterRegistry.find(metric).tag(PRODUCT_TAG, productId.toString()).meters().forEach(meterRegistry::remove);
        }
    }
}
