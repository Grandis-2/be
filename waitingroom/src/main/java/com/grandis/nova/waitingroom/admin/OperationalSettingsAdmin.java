package com.grandis.nova.waitingroom.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.waitingroom.admin.AdmissionRateView.CapacityGuide;
import com.grandis.nova.waitingroom.admin.AdmissionRateView.ProductRate;
import com.grandis.nova.waitingroom.admin.AdmissionRateView.Setting;
import com.grandis.nova.waitingroom.admin.AdmissionRateView.Source;
import com.grandis.nova.waitingroom.admin.WaitingroomStatusView.ProductStatus;
import com.grandis.nova.waitingroom.control.AdmissionProperties;
import com.grandis.nova.waitingroom.control.GatewaySnapshot;
import com.grandis.nova.waitingroom.control.Leadership;
import com.grandis.nova.waitingroom.control.OperationalSettings;
import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.control.SnapshotHolder;
import com.grandis.nova.waitingroom.domain.admission.AdmissionDecider;
import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.queue.LeaveGrace;
import com.grandis.nova.waitingroom.redis.ControlStore;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;

/**
 * 운영값(초당 입장 인원 · 모델별 상한 · 최대 대기 시간 · 이탈 유예)을 Redis 에 쓰고 읽는다. 바꾼 사람 · 시각 · 전후 값을 남긴다.
 * 전역 0 은 입장 일시 정지다(줄은 그대로). 상한을 낮춰도 이미 선 사람은 빼지 않는다.
 */
@Component
class OperationalSettingsAdmin {

    static final long MAX_WAIT_SECONDS = Duration.ofDays(1).toSeconds();
    /** 판정한 뒤 다른 관리자가 바꿔 다시 읽는 횟수. 넘으면 503 — 사람이 동시에 바꾸는 일이라 몇 번이면 충분하다. */
    private static final int SETTING_RACE_RETRIES = 3;
    private static final Logger log = LoggerFactory.getLogger(OperationalSettingsAdmin.class);

    private final ControlStore store;
    private final AdmissionProperties defaults;
    private final SnapshotHolder snapshots;
    private final RedisClock clock;
    private final Leadership leadership;
    private final MeterRegistry registry;

    OperationalSettingsAdmin(ControlStore store, AdmissionProperties defaults, SnapshotHolder snapshots,
                             RedisClock clock, Leadership leadership, MeterRegistry registry) {
        this.store = store;
        this.defaults = defaults;
        this.snapshots = snapshots;
        this.clock = clock;
        this.leadership = leadership;
        this.registry = registry;
    }

    Mono<AdmissionRateView> admissionRate() {
        return store.readSettings().map(this::view).onErrorMap(OperationalSettingsAdmin::storeFailure, OperationalSettingsAdmin::unavailable);
    }

    /**
     * 늘려서 preorder 용량 합을 넘기면 confirm 이 있어야 저장한다. 증설로도 닿지 않는 값은 거절한다.
     * 판정한 값이 그대로일 때만 쓴다 — 그사이 다른 관리자가 바꿨으면 다시 읽고 판정한다.
     */
    Mono<AdmissionRateView> setGlobalCredit(String actor, Long value, boolean confirmed) {
        long checked = checked("globalCredit", value, 0, defaults.maxGlobalCredit());
        Mono<Optional<String>> write = Mono.defer(() -> store.readSettings().flatMap(raw -> {
                    long current = settings(raw).globalCredit();
                    AdmissionRateGuide.check(checked, current, confirmed, guide(current));
                    return store.compareAndSetSetting(OperationalSettings.GLOBAL_CREDIT,
                            Optional.ofNullable(raw.get(OperationalSettings.GLOBAL_CREDIT)), String.valueOf(checked));
                }))
                .repeatWhenEmpty(SETTING_RACE_RETRIES, attempts -> attempts)
                .switchIfEmpty(Mono.error(() -> new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE)));
        return change(actor, OperationalSettings.GLOBAL_CREDIT, write, String.valueOf(checked));
    }

    Mono<AdmissionRateView> setProductCap(String actor, String productId, Long value) {
        String field = RedisKeys.capField(checkedProduct(productId));
        long checked = checked("cap", value, 1, defaults.maxGlobalCredit());
        return change(actor, field, store.writeSetting(field, String.valueOf(checked)), String.valueOf(checked));
    }

    Mono<AdmissionRateView> setMaxWait(String actor, Long seconds) {
        long checked = checked("seconds", seconds, 1, MAX_WAIT_SECONDS);
        return change(actor, OperationalSettings.MAX_WAIT_SEC, store.writeSetting(OperationalSettings.MAX_WAIT_SEC,
                String.valueOf(checked)), String.valueOf(checked));
    }

    /** 다음 조회부터 적용된다. 이미 써 둔 생존 신호는 그대로라 늘린 직후에는 옛 시간으로 걷힐 수 있다. */
    Mono<AdmissionRateView> setLeaveGrace(String actor, Long seconds) {
        long checked = checked("seconds", seconds, LeaveGrace.MIN.toSeconds(), LeaveGrace.MAX.toSeconds());
        return change(actor, OperationalSettings.LEAVE_GRACE_SEC, store.writeSetting(OperationalSettings.LEAVE_GRACE_SEC,
                String.valueOf(checked)), String.valueOf(checked));
    }

    Mono<AdmissionRateView> clear(String actor, String field) {
        return change(actor, field, store.clearSetting(field), null);
    }

    Mono<AdmissionRateView> clearProductCap(String actor, String productId) {
        return clear(actor, RedisKeys.capField(checkedProduct(productId)));
    }

    Mono<WaitingroomStatusView> status() {
        return store.readLeaderOwner().map(Optional::of).defaultIfEmpty(Optional.empty())
                .onErrorMap(OperationalSettingsAdmin::storeFailure, OperationalSettingsAdmin::unavailable)
                .map(leader -> {
                    Optional<GatewaySnapshot> snapshot = snapshots.current();
                    return new WaitingroomStatusView(leadership.nodeId(), leader.orElse(null), snapshots.stale(),
                            snapshot.map(current -> snapshots.ageMillis() / 1000).orElse(null),
                            snapshot.map(current -> current.meta().gatewayCount()).orElse(null),
                            snapshot.map(current -> current.meta().globalCredit()).orElse(null),
                            snapshot.map(GatewaySnapshot::brakeFactor).orElse(null),
                            snapshot.map(current -> current.meta().maxWait().duration()).map(Duration::toSeconds).orElse(null),
                            snapshot.map(current -> current.meta().leaveGrace().toSeconds()).orElse(null),
                            snapshot.map(this::products).orElse(List.of()));
                });
    }

    private List<ProductStatus> products(GatewaySnapshot snapshot) {
        return snapshot.products().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    ProductState state = entry.getValue();
                    return new ProductStatus(entry.getKey(), state.phaseAt(clock.now()).name(), state.runtime().name(),
                            state.waiting(), state.credit(), state.cap() == ProductState.UNLIMITED_CAP ? null : state.cap(),
                            state.window().opensAt(), state.window().closesAt());
                })
                .toList();
    }

    private Mono<AdmissionRateView> change(String actor, String field, Mono<Optional<String>> write, String after) {
        return write
                .doOnNext(before -> {
                    log.info("운영값 변경 actor={} field={} before={} after={}", actor, field, before.orElse("(기본값)"),
                            after == null ? "(기본값)" : after);
                    Counter.builder("waitingroom.admin.changes").tag("field", field.startsWith("cap:") ? "cap" : field)
                            .register(registry).increment();
                })
                .then(admissionRate())
                .onErrorMap(OperationalSettingsAdmin::storeFailure, OperationalSettingsAdmin::unavailable);
    }

    private OperationalSettings settings(Map<String, String> raw) {
        return OperationalSettings.from(raw, defaults.globalCredit(), defaults.productCap(), defaults.maxGlobalCredit());
    }

    /** 낡은 재료로는 기준을 내지 않는다 — 리더가 멈췄으면 용량도 적용값도 지금 것이 아니다. */
    private CapacityGuide guide(long operationalCredit) {
        Optional<GatewaySnapshot> snapshot = snapshots.stale() ? Optional.empty() : snapshots.current();
        return AdmissionRateGuide.of(snapshot, operationalCredit);
    }

    private AdmissionRateView view(Map<String, String> raw) {
        OperationalSettings settings = settings(raw);
        // 출처는 리더가 실제로 받아들인 값인지로 정한다 — 깨진 값이 저장돼 있으면 리더는 기본값을 쓴다
        Setting globalCredit = new Setting(settings.globalCredit(),
                OperationalSettings.acceptsGlobalCredit(raw.get(OperationalSettings.GLOBAL_CREDIT)) ? Source.OPERATIONAL : Source.DEFAULT);
        MaxWait maxWait = settings.maxWait();
        Setting maxWaitSeconds = new Setting(maxWait.duration() == null ? null : maxWait.duration().toSeconds(),
                OperationalSettings.acceptsMaxWait(raw.get(OperationalSettings.MAX_WAIT_SEC)) ? Source.OPERATIONAL : Source.DEFAULT);
        Setting leaveGraceSeconds = new Setting(settings.leaveGrace().toSeconds(),
                OperationalSettings.acceptsLeaveGrace(raw.get(OperationalSettings.LEAVE_GRACE_SEC)) ? Source.OPERATIONAL : Source.DEFAULT);
        Map<String, ProductState> states = snapshots.current().map(GatewaySnapshot::products).orElse(Map.of());
        TreeSet<String> productIds = new TreeSet<>(states.keySet());
        productIds.addAll(settings.caps().keySet());
        return new AdmissionRateView(globalCredit, defaults.maxGlobalCredit(), maxWaitSeconds, leaveGraceSeconds,
                productIds.stream()
                        .map(productId -> new ProductRate(productId, cap(settings, productId),
                                queueLimit(states.get(productId), maxWait)))
                        .toList(),
                guide(settings.globalCredit()));
    }

    private static Setting cap(OperationalSettings settings, String productId) {
        long cap = settings.capOf(productId);
        return new Setting(cap == ProductState.UNLIMITED_CAP ? null : cap,
                settings.caps().containsKey(productId) ? Source.OPERATIONAL : Source.DEFAULT);
    }

    /** 진입 판정과 같은 식 — 속도를 모르면(0 · 판정 재료에 아직 없음) 가장 낮은 속도를 가정한다. 제한 없음이면 null. */
    private static Long queueLimit(ProductState state, MaxWait maxWait) {
        if (maxWait.duration() == null) {
            return null;
        }
        long credit = state == null ? 0 : state.credit();
        return maxWait.capacity(Math.max(credit, AdmissionDecider.MIN_CREDIT));
    }

    /** 정수만 받는다 — 0.5 가 0(입장 정지)으로 잘리거나 "5" 같은 문자열이 들어오지 않게. */
    static Long integral(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, field + " 는 정수여야 합니다.",
                    Map.of("field", field));
        }
        return value.longValue();
    }

    private static long checked(String field, Long value, long min, long max) {
        if (value == null || value < min || value > max) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED,
                    "%s 는 %d 이상 %d 이하여야 합니다.".formatted(field, min, max), Map.of("field", field));
        }
        return value;
    }

    private static String checkedProduct(String productId) {
        if (!RedisKeys.validProductKey(productId)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, "productId 가 올바르지 않습니다.",
                    Map.of("field", "productId"));
        }
        return productId;
    }

    private static boolean storeFailure(Throwable e) {
        return e instanceof DataAccessException || e instanceof TimeoutException;
    }

    private static BusinessException unavailable(Throwable cause) {
        log.warn("운영값 저장소를 쓰지 못했다: {}", cause.toString());
        return new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
