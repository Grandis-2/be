package com.grandis.nova.waitingroom.admin;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.waitingroom.WaitingroomErrorCode;
import com.grandis.nova.waitingroom.control.AdmissionProperties;
import com.grandis.nova.waitingroom.control.CapacityStatus;
import com.grandis.nova.waitingroom.control.Leadership;
import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.control.SnapshotHolder;
import com.grandis.nova.waitingroom.control.TestSnapshots;
import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import com.grandis.nova.waitingroom.redis.ControlStore;
import com.grandis.nova.waitingroom.redis.LuaScripts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Redis 가 안 되면 관리자 요청은 503 이고 바꿨다는 감사 로그 · 지표를 남기지 않는다. */
class OperationalSettingsAdminTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ControlStore down = new ControlStore(new LuaScripts(null), null) {
        @Override
        public Mono<Map<String, String>> readSettings() {
            return Mono.error(new RedisConnectionFailureException("down"));
        }

        @Override
        public Mono<Optional<String>> writeSetting(String field, String value) {
            return Mono.error(new RedisConnectionFailureException("down"));
        }

        @Override
        public Mono<Optional<String>> clearSetting(String field) {
            return Mono.error(new RedisConnectionFailureException("down"));
        }

        @Override
        public Mono<String> readLeaderOwner() {
            return Mono.error(new RedisConnectionFailureException("down"));
        }
    };
    private final OperationalSettingsAdmin admin = new OperationalSettingsAdmin(down, new AdmissionProperties(null, null),
            TestSnapshots.emptyHolder(), new RedisClock(Clock.systemUTC()), new Leadership(), registry);

    @Test
    void 저장소_장애면_조회_변경_기본값_복귀_현황_모두_503_이다() {
        unavailable(admin.admissionRate());
        unavailable(admin.setGlobalCredit("admin", 10L, false));
        unavailable(admin.clear("admin", "global-credit"));
        unavailable(admin.setMaxWait("admin", 60L));
        unavailable(admin.status());

        assertThat(registry.find("waitingroom.admin.changes").counter())
                .as("바꾸지 못했으니 변경을 세지 않는다").isNull();
    }

    @Test
    void 판정한_뒤_다른_관리자가_바꿨으면_다시_읽고_판정한다() {
        // 400 에서 300 으로 줄이려는 사이 다른 관리자가 260 으로 내렸다 — 300 은 이제 용량 합(250)을 넘겨 늘리는 것이다
        Map<String, String> stored = new HashMap<>(Map.of("global-credit", "400"));
        ControlStore racing = new ControlStore(new LuaScripts(null), null) {
            @Override
            public Mono<Map<String, String>> readSettings() {
                return Mono.fromSupplier(() -> Map.copyOf(stored));
            }

            @Override
            public Mono<Optional<String>> compareAndSetSetting(String field, Optional<String> expected, String value) {
                return Mono.defer(() -> {
                    if (stored.get(field).equals("400")) {
                        stored.put(field, "260");
                    }
                    if (!expected.equals(Optional.ofNullable(stored.get(field)))) {
                        return Mono.empty();
                    }
                    return Mono.just(Optional.ofNullable(stored.put(field, value)));
                });
            }
        };
        SnapshotHolder holder = TestSnapshots.emptyHolder();
        TestSnapshots.put(holder, new SnapshotMeta(250, 1, MaxWait.unlimited()), 1.0,
                new CapacityStatus(400, 250, 3, 0, 500, false), Optional.empty());
        OperationalSettingsAdmin admin = new OperationalSettingsAdmin(racing, new AdmissionProperties(null, null), holder,
                new RedisClock(Clock.systemUTC()), new Leadership(), registry);

        StepVerifier.create(admin.setGlobalCredit("admin", 300L, false))
                .expectErrorMatches(e -> e instanceof BusinessException business
                        && business.errorCode() == WaitingroomErrorCode.ADMISSION_RATE_NEEDS_CONFIRM)
                .verify();
        assertThat(stored).containsEntry("global-credit", "260");
    }

    private static void unavailable(Mono<?> call) {
        StepVerifier.create(call)
                .expectErrorMatches(e -> e instanceof BusinessException business
                        && business.errorCode() == CommonErrorCode.DEPENDENCY_UNAVAILABLE)
                .verify();
    }
}
