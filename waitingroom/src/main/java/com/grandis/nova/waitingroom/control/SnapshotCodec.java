package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.RuntimeState;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
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
    static final String PUBLISHED_AT = "#at";

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
        fields.put(PUBLISHED_AT, String.valueOf(snapshot.publishedAtMillis()));
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
            return Optional.of(new GatewaySnapshot(products, new SnapshotMeta(globalCredit, gateways, maxWait), publishedAt));
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
}
