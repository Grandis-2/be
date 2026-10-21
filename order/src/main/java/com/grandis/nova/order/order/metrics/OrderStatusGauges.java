package com.grandis.nova.order.order.metrics;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 배송을 기다리는 주문 수 — order.status.count{status}(상품 준비 · 출고 대기). 관리자 알림 SHIPPING_BACKLOG 는 Grafana 규칙이 두 상태의 합으로
 * 판정한다(contracts/admin-alert-events.md). 모든 인스턴스가 같은 값을 내므로 규칙은 인스턴스를 더하지 않고 max 로 묶는다.
 *
 * 수집할 때마다 DB 를 묻지 않도록 기동하자마자 한 번, 그 뒤 주기마다(기본 30초, nova.metrics.refresh-interval) 세어 두고 게이지는 그 값을 읽는다.
 * 두 상태만 센다 — 계속 늘어나는 끝 상태(취소 · 배송 완료)까지 주기마다 읽지 않게. 첫 읽기 전은 NaN 이다 — 0 은 "배송 대기 없음"으로 읽혀, 경보가
 * 열린 채 배포하면 해소됐다 다시 열린다. 읽은 뒤 실패하면 앞의 값을 둔다(WARN 로그). 계속 실패하면 NaN 이나 앞의 값에 머물러 경보가 이 지표로는
 * 울리지 않는다 — 갱신 실패를 따로 알리는 지표는 두지 않는다(preorder StateGauges 와 같다).
 */
@Component
class OrderStatusGauges {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusGauges.class);

    static final String METRIC = "order.status.count";
    static final Set<OrderStatus> STATUSES = Set.of(OrderStatus.PREPARING_ITEMS, OrderStatus.READY_TO_SHIP);

    private final OrderReader orders;
    private final Map<OrderStatus, AtomicLong> counts = new EnumMap<>(OrderStatus.class);
    private volatile boolean read;

    OrderStatusGauges(OrderReader orders, MeterRegistry registry) {
        this.orders = orders;
        for (OrderStatus status : STATUSES) {
            AtomicLong count = new AtomicLong();
            counts.put(status, count);
            Gauge.builder(METRIC, count, c -> read ? c.get() : Double.NaN).tag("status", status.name())
                    .description("그 상태의 주문 수(배송 대기)").register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${nova.metrics.refresh-interval:30s}")
    void refresh() {
        Map<OrderStatus, Long> latest;
        try {
            latest = orders.countByStatus(STATUSES);
        } catch (RuntimeException e) {
            log.warn("주문 상태 지표 갱신 실패 — 앞의 값을 둔다", e);
            return;
        }
        // 새 값을 다 센 뒤에 바꾼다 — 먼저 0 으로 비우면 그사이 수집에 0 이 잡힌다
        counts.forEach((status, count) -> count.set(latest.getOrDefault(status, 0L)));
        read = true;
    }
}
