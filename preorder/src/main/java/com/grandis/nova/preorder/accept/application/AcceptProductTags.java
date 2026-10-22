package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.preorder.campaign.Campaigns;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 접수 건수 지표의 상품 라벨. 접수 중인 회차만 상품 id 로 내고 나머지는 other 로 묶는다 — 상품 시계열 수는 동시에 접수 중인 회차 수다.
 * 다음 갱신 전에 열릴 회차도 미리 넣는다(오픈 직후 접수가 other 로 빠지지 않게). 응답 시간 지표에는 붙이지 않는다(히스토그램이라 무겁다).
 *
 * 정리는 갱신마다 쓸어 낸다 — 이번과 지난 갱신의 집합 밖인 상품 시계열을 지운다. 벗어난 상품은 한 주기 남아 마지막 증가분이 수집되고,
 * 정리와 겹친 요청이 지운 시계열을 다시 만들어도 다음 갱신에 지워진다.
 */
@Component
class AcceptProductTags {

    static final String PRODUCT_TAG = "productId";
    static final String OTHER = "other";
    private static final Logger log = LoggerFactory.getLogger(AcceptProductTags.class);
    private static final List<String> METRICS = List.of(PreorderAcceptService.CREATED_METRIC,
            AcceptBulkhead.REJECTED_METRIC);

    private final Campaigns campaigns;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    /** 갱신 주기의 두 배 — 한 번 늦어도 열리기 전에 들어간다. */
    private final Duration lookahead;
    private volatile Set<UUID> accepting = Set.of();

    AcceptProductTags(Campaigns campaigns, MeterRegistry meterRegistry, Clock clock,
                      @Value("${nova.metrics.refresh-interval:30s}") Duration refreshInterval) {
        this.campaigns = campaigns;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.lookahead = refreshInterval.multipliedBy(2);
    }

    String of(UUID productId) {
        return accepting.contains(productId) ? productId.toString() : OTHER;
    }

    /**
     * 준비 상태가 되기 전에 한 번 센다 — 증설된 태스크가 첫 주기 동안 모두 other 로 내지 않게. 실패해도 기동은 막지 않는다
     * (라벨만 other 로 묶이고 다음 주기에 다시 센다).
     */
    @EventListener(ApplicationReadyEvent.class)
    void refreshOnStart() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("접수 지표의 상품 라벨을 처음 세지 못했다 — 다음 주기까지 other 로 묶는다", e);
        }
    }

    @Scheduled(fixedDelayString = "${nova.metrics.refresh-interval:30s}",
            initialDelayString = "${nova.metrics.refresh-interval:30s}")
    void refresh() {
        Set<UUID> now = campaigns.findAcceptingProductIds(clock.instant(), lookahead);
        Set<UUID> previous = accepting;
        // 라벨을 내기 전에 0 으로 만든다 — 첫 증가가 시계열의 시작이 되면 rate 가 그 한 건을 놓친다
        now.stream().filter(productId -> !previous.contains(productId)).map(UUID::toString).forEach(tag -> {
            PreorderAcceptService.registerCreated(meterRegistry, tag);
            AcceptBulkhead.registerRejected(meterRegistry, tag);
        });
        accepting = now;
        Set<String> kept = new HashSet<>();
        now.forEach(productId -> kept.add(productId.toString()));
        previous.forEach(productId -> kept.add(productId.toString()));
        for (String metric : METRICS) {
            for (Meter meter : meterRegistry.find(metric).meters()) {
                String tag = meter.getId().getTag(PRODUCT_TAG);
                if (tag != null && !OTHER.equals(tag) && !kept.contains(tag)) {
                    meterRegistry.remove(meter);
                }
            }
        }
    }
}
