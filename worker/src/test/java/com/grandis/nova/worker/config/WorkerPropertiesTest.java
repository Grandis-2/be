package com.grandis.nova.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 설정이 없으면 기본값으로 묶이고, 서로 어긋나는 값은 기동을 막는다. */
class WorkerPropertiesTest {

    @Test
    void 주소만_주면_나머지는_기본값이다() {
        WorkerProperties properties = bind(Map.of());

        assertThat(properties.mock().connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.mock().readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.mock().slowCallThreshold()).isEqualTo(Duration.ofMillis(1500));
        assertThat(properties.concurrency().limit()).isEqualTo(16);
        assertThat(properties.concurrency().rateLimit()).isEqualTo(40);
        assertThat(properties.concurrency().initial()).isEqualTo(8);
        assertThat(properties.circuit().openFirst()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.circuit().openMax()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.retry().backoff())
                .containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(40));
        assertThat(properties.retry().generations()).isEqualTo(3);
        assertThat(properties.retry().generationGap()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.consumer().enabled()).isTrue();
        assertThat(properties.consumer().settings("preorder-cancel").visibility()).isEqualTo(Duration.ofSeconds(120));
        assertThat(properties.rejectGuard().invalidRequests()).isEqualTo(5);
        assertThat(properties.rejectGuard().pause()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.mock().validateAfterInactivity()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.mock().maxResponseBody().toBytes()).isEqualTo(64 * 1024);
        assertThat(properties.concurrency().increaseStep()).isEqualTo(1);
        assertThat(properties.concurrency().decreaseFactor()).isEqualTo(0.5);
        assertThat(properties.circuit().openMultiplier()).isEqualTo(2);
    }

    @Test
    void mock_대수가_늘면_상한도_그만큼_는다() {
        WorkerProperties properties = bind(Map.of("nova.worker.concurrency.mock-max-instances", "4"));

        assertThat(properties.concurrency().limit()).isEqualTo(64);
        assertThat(properties.concurrency().rateLimit()).isEqualTo(160);
    }

    @Test
    void mock_주소가_없으면_기동을_막는다() {
        assertThatThrownBy(() -> bindRaw(Map.of())).rootCause().hasMessageContaining("mock.base-url");
    }

    @Test
    void 어긋나는_값은_기동을_막는다() {
        for (Map<String, String> invalid : List.of(
                Map.of("nova.worker.mock.slow-call-threshold", "3s"),
                Map.of("nova.worker.concurrency.initial", "17"),
                Map.of("nova.worker.concurrency.min", "9"),
                Map.of("nova.worker.concurrency.cancel-reserved", "2"),
                Map.of("nova.worker.concurrency.unhealthy-failure-rate", "5"),
                Map.of("nova.worker.concurrency.unhealthy-failure-rate", "101"),
                Map.of("nova.worker.concurrency.increase-step", "0"),
                Map.of("nova.worker.concurrency.decrease-factor", "1"),
                Map.of("nova.worker.circuit.open-multiplier", "0.5"),
                Map.of("nova.worker.mock.max-response-body", "10B"),
                Map.of("nova.worker.mock.validate-after-inactivity", "0s"),
                Map.of("nova.worker.reject-guard.pause", "0s"),
                Map.of("nova.worker.concurrency.per-mock", "65536", "nova.worker.concurrency.mock-max-instances", "65536"),
                Map.of("nova.worker.concurrency.rate-per-second", "2147483647",
                        "nova.worker.concurrency.mock-max-instances", "2"),
                Map.of("nova.worker.circuit.minimum-calls", "21"),
                Map.of("nova.worker.circuit.failure-rate", "0"),
                Map.of("nova.worker.circuit.open-max", "16m"),
                Map.of("nova.worker.retry.backoff", "10s,20s"),
                Map.of("nova.worker.retry.generation-gap", "16m"),
                Map.of("nova.worker.consumer.wait-seconds", "0"),
                Map.of("nova.worker.consumer.wait-seconds", "21"),
                Map.of("nova.worker.consumer.visibility", "10s"),
                Map.of("nova.worker.reject-guard.invalid-requests", "0"))) {
            assertThatThrownBy(() -> bind(invalid)).as(invalid.toString())
                    .rootCause().isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static WorkerProperties bind(Map<String, String> properties) {
        Map<String, String> all = new HashMap<>(properties);
        all.putIfAbsent("nova.worker.mock.base-url", "http://mock.nova.local:8081");
        return bindRaw(all);
    }

    private static WorkerProperties bindRaw(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("nova.worker", WorkerProperties.class);
    }
}
