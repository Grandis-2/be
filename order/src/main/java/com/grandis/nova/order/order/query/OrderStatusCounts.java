package com.grandis.nova.order.order.query;

import com.grandis.nova.common.redis.cache.ReadCache;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 화면의 상태별 주문 수. 모든 상태를 상태 선언 순서로, 주문이 없는 상태는 0 으로 준다. 센 시각(countedAt)은 이 서버의 시계다.
 *
 * 전체 수는 상태로 시작하는 색인을 처음부터 끝까지 읽어(행은 읽지 않는다) 비용이 전체 주문 수에 비례하므로 Redis 에 {@link #TTL} 동안 둔다 — 여러
 * 관리자 · 인스턴스가 불러도 DB 는 그 주기에 한 번쯤 센다. 그만큼 늦게 바뀐다(목록과 최대 {@link #TTL} 어긋난다). 세대를 올리지 않는다 — 주문
 * 상태는 곳곳(결제 · 배송 · 취소 수신)에서 바뀌어 짧은 TTL 로만 맞춘다. Redis 를 쓸 수 없으면 바로 센다(ReadCache).
 *
 * 상품별 수는 캐시하지 않는다 — 키가 요청의 상품 id 마다 생겨 끝없이 늘 수 있다. 그 상품의 주문만 읽어 비용이 그 상품의 주문 수에 비례한다.
 * 트랜잭션을 열지 않는다 — 세는 문장 하나다(OrderReader).
 */
@Component
public class OrderStatusCounts {

    static final Duration TTL = Duration.ofSeconds(5);
    private static final String GENERATION = "order-stats";
    /** 값은 상태 수만큼의 짧은 표다. */
    private static final long BUDGET_BYTES = 64 * 1024;

    private final OrderReader reader;
    private final ReadCache cache;
    private final Clock clock;
    private final ReadCache.Spec<Snapshot> spec;

    public OrderStatusCounts(OrderReader reader, ReadCache cache, Clock clock) {
        this.reader = reader;
        this.cache = cache;
        this.clock = clock;
        this.spec = cache.spec("order-stats", new TypeReference<Snapshot>() { }, TTL, BUDGET_BYTES);
    }

    public Snapshot all() {
        return cache.get(spec, GENERATION, "all", () -> snapshot(reader.countByStatus(EnumSet.allOf(OrderStatus.class))));
    }

    public Snapshot ofProduct(UUID productId) {
        return snapshot(reader.countByStatusOfProduct(productId));
    }

    private Snapshot snapshot(Map<OrderStatus, Long> counts) {
        Map<OrderStatus, Long> byStatus = new LinkedHashMap<>();
        for (OrderStatus status : OrderStatus.values()) {
            byStatus.put(status, counts.getOrDefault(status, 0L));
        }
        return new Snapshot(byStatus, clock.instant());
    }

    /** @param byStatus 모든 상태, 상태 선언 순서 */
    public record Snapshot(Map<OrderStatus, Long> byStatus, Instant countedAt) {
    }
}
