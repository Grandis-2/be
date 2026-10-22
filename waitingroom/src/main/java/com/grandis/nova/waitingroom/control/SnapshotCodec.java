package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;
import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.RuntimeState;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import com.grandis.nova.waitingroom.domain.queue.LeaveGrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 판정 재료 ↔ Redis 해시. 모델 필드는 "p:{모델}" = "상태|credit|waiting|cap|opensAt(ms)|closesAt(ms)",
 * 전역 값은 '#' 로 시작한다. 깨진 모델 필드는 그 모델만 빼고 읽는다.
 */
final class SnapshotCodec {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCodec.class);

    static final String PRODUCT_PREFIX = "p:";
    static final String GLOBAL_CREDIT = "#credit";
    static final String GATEWAYS = "#gateways";
    static final String MAX_WAIT = "#max-wait-sec";
    static final String LEAVE_GRACE = "#leave-grace-sec";
    static final String PUBLISHED_AT = "#at";
    static final String BRAKE = "#brake";
    /** "운영값|용량 합|다 뜬 태스크|갓 뜬 태스크|태스크당 용량". 용량을 모르면 합 · 태스크당 용량이 -1 이다. */
    static final String CAPACITY = "#capacity";
    /** 리더 브레이크 창의 "전달|나쁜 응답". 브레이크가 꺼졌으면 없다. */
    static final String RELAY = "#relay";

    private SnapshotCodec() {
    }

    static Map<String, String> encode(GatewaySnapshot snapshot) {
        Map<String, String> fields = new LinkedHashMap<>();
        snapshot.products().forEach((key, state) -> fields.put(PRODUCT_PREFIX + key, String.join("|",
                state.runtime().name(), String.valueOf(state.credit()), String.valueOf(state.waiting()),
                String.valueOf(state.cap()), String.valueOf(state.window().opensAt().toEpochMilli()),
                String.valueOf(state.window().closesAt().toEpochMilli()))));
        SnapshotMeta meta = snapshot.meta();
        fields.put(GLOBAL_CREDIT, String.valueOf(meta.globalCredit()));
        fields.put(GATEWAYS, String.valueOf(meta.gatewayCount()));
        fields.put(MAX_WAIT, meta.maxWait().duration() == null ? "-1" : String.valueOf(meta.maxWait().duration().toSeconds()));
        fields.put(LEAVE_GRACE, String.valueOf(meta.leaveGrace().toSeconds()));
        fields.put(PUBLISHED_AT, String.valueOf(snapshot.publishedAtMillis()));
        fields.put(BRAKE, String.valueOf(snapshot.brakeFactor()));
        CapacityStatus capacity = snapshot.capacity();
        fields.put(CAPACITY, capacity.operationalCredit() + "|" + capacity.sum() + "|" + capacity.ready() + "|"
                + capacity.warming() + "|" + capacity.perTask());
        snapshot.relayWindow().ifPresent(relay -> fields.put(RELAY, relay.relayed() + "|" + relay.bad()));
        return fields;
    }

    /** 전역 값이 없거나 깨졌으면 빈 값 — 반쪽 재료로 판정하지 않는다. */
    static Optional<GatewaySnapshot> decode(Map<String, String> fields) {
        try {
            long globalCredit = Long.parseLong(fields.get(GLOBAL_CREDIT));
            int gateways = Integer.parseInt(fields.get(GATEWAYS));
            long maxWaitSec = Long.parseLong(fields.get(MAX_WAIT));
            long publishedAt = Long.parseLong(fields.get(PUBLISHED_AT));
            MaxWait maxWait = maxWaitSec > 0 ? MaxWait.of(Duration.ofSeconds(maxWaitSec)) : MaxWait.unlimited();
            Map<String, ProductState> products = new LinkedHashMap<>();
            fields.forEach((field, value) -> {
                if (field.startsWith(PRODUCT_PREFIX)) {
                    product(value).ifPresentOrElse(state -> products.put(field.substring(PRODUCT_PREFIX.length()), state),
                            () -> log.debug("판정 재료의 모델 필드를 읽지 못해 뺀다: {}", field));
                }
            });
            return Optional.of(new GatewaySnapshot(products, new SnapshotMeta(globalCredit, gateways, maxWait,
                    leaveGrace(fields.get(LEAVE_GRACE))), publishedAt, brakeFactor(fields.get(BRAKE)),
                    capacity(fields.get(CAPACITY)), relay(fields.get(RELAY))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<ProductState> product(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 6) {
            return Optional.empty();
        }
        try {
            SalesWindow window = new SalesWindow(Instant.ofEpochMilli(Long.parseLong(parts[4])),
                    Instant.ofEpochMilli(Long.parseLong(parts[5])));
            return Optional.of(new ProductState(RuntimeState.valueOf(parts[0]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), Long.parseLong(parts[3]), window));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 없거나 깨졌으면 기본값으로 읽는다 — 이 필드를 모르는 옛 리더가 낸 재료도 판정에 써야 한다. */
    private static Duration leaveGrace(String value) {
        try {
            long seconds = value == null ? -1 : Long.parseLong(value);
            return LeaveGrace.accepts(seconds) ? Duration.ofSeconds(seconds) : LeaveGrace.MIN;
        } catch (NumberFormatException e) {
            return LeaveGrace.MIN;
        }
    }

    /** 없거나 깨졌으면 운영값도 용량도 모르는 것으로 읽는다 — 이 필드를 모르는 옛 리더가 낸 재료다. 판정에는 쓰지 않고 보이기만 한다. */
    private static CapacityStatus capacity(String value) {
        String[] parts = value == null ? new String[0] : value.split("\\|", -1);
        if (parts.length != 5) {
            return CapacityStatus.MISSING;
        }
        try {
            long operational = Long.parseLong(parts[0]);
            long sum = Long.parseLong(parts[1]);
            int ready = Integer.parseInt(parts[2]);
            int warming = Integer.parseInt(parts[3]);
            long perTask = Long.parseLong(parts[4]);
            if (operational < CapacityStatus.UNKNOWN || sum < CapacityStatus.UNKNOWN || ready < 0 || warming < 0
                    || perTask < CapacityStatus.UNKNOWN) {
                return CapacityStatus.MISSING;
            }
            return new CapacityStatus(operational, sum, ready, warming, perTask);
        } catch (NumberFormatException e) {
            return CapacityStatus.MISSING;
        }
    }

    /** 없거나 깨졌으면 모르는 것으로 읽는다 — 보이기만 하고 판정에는 쓰지 않는다. */
    private static Optional<RelayOutcome> relay(String value) {
        String[] parts = value == null ? new String[0] : value.split("\\|", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            RelayOutcome relay = new RelayOutcome(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            return relay.relayed() >= 0 && relay.bad() >= 0 ? Optional.of(relay) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 없거나 깨졌으면 걸리지 않은 것으로 읽는다 — 브레이크는 속도를 줄이기만 하므로 모를 때 줄이지 않는다. */
    private static double brakeFactor(String value) {
        try {
            double factor = value == null ? 1.0 : Double.parseDouble(value);
            return factor > 0 && factor <= 1 ? factor : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }
}
