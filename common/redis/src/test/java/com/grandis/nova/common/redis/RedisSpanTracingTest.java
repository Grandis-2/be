package com.grandis.nova.common.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Lettuce 명령이 보낸 스레드의 스팬 아래 클라이언트 스팬으로 남고, 감싼 Micrometer 관측(lettuce 지표)도 그대로 남는다. */
class RedisSpanTracingTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("valkey/valkey:9").withExposedPorts(6379);

    static InMemorySpanExporter exported;
    static SdkTracerProvider tracerProvider;
    static Tracer tracer;
    static SimpleMeterRegistry meters;
    static ClientResources resources;
    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;

    @BeforeAll
    static void start() {
        REDIS.start();
        exported = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build();
        tracer = tracerProvider.get("test");
        meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        resources = ClientResources.builder()
                .tracing(new RedisSpanTracing(new MicrometerTracing(observations, "Redis"),
                        tracerProvider.get(RedisTracingAutoConfiguration.INSTRUMENTATION)::spanBuilder))
                .build();
        client = RedisClient.create(resources, RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connection = client.connect();
    }

    @AfterAll
    static void stop() {
        connection.close();
        client.shutdown();
        resources.shutdown();
        tracerProvider.close();
        REDIS.stop();
    }

    @BeforeEach
    void clear() {
        exported.reset();
    }

    @Test
    void 보낸_스레드의_스팬_아래_클라이언트_스팬으로_남는다() {
        RedisCommands<String, String> redis = connection.sync();
        Span parent = tracer.spanBuilder("request").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            redis.set("span-key", "v");
            redis.get("span-key");
        } finally {
            parent.end();
        }

        List<SpanData> commands = redisSpans(2);
        assertThat(commands).extracting(SpanData::getName).containsExactly("SET", "GET");
        assertThat(commands).allSatisfy(span -> {
            assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
            assertThat(span.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(span.getTraceId()).isEqualTo(parent.getSpanContext().getTraceId());
            assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system"))).isEqualTo("redis");
            assertThat(span.getAttributes().get(AttributeKey.longKey("server.port")))
                    .isEqualTo((long) REDIS.getMappedPort(6379));
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        });
        assertThat(commands.get(1).getAttributes().get(AttributeKey.stringKey("db.operation"))).isEqualTo("GET");
    }

    @Test
    void 오류_응답은_오류_스팬이고_지표는_그대로_남는다() {
        RedisCommands<String, String> redis = connection.sync();
        redis.set("not-a-number", "text");
        exported.reset();

        assertThatThrownBy(() -> redis.incr("not-a-number")).isInstanceOf(RedisCommandExecutionException.class);

        SpanData incr = redisSpans(1).getFirst();
        assertThat(incr.getName()).isEqualTo("INCR");
        assertThat(incr.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(meters.find("lettuce").timers()).isNotEmpty();
    }

    /** 완료 콜백은 이벤트 루프에서 스팬을 닫으므로 내보내기를 기다린다. */
    private static List<SpanData> redisSpans(int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> commandSpans().size() >= count);
        return commandSpans();
    }

    private static List<SpanData> commandSpans() {
        return exported.getFinishedSpanItems().stream()
                .filter(span -> span.getInstrumentationScopeInfo().getName().equals(RedisTracingAutoConfiguration.INSTRUMENTATION))
                .toList();
    }
}
