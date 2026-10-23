package com.grandis.nova.common.redis;

import io.lettuce.core.protocol.CompleteableCommand;
import io.lettuce.core.protocol.RedisCommand;
import io.lettuce.core.tracing.TraceContext;
import io.lettuce.core.tracing.TraceContextProvider;
import io.lettuce.core.tracing.Tracer;
import io.lettuce.core.tracing.TracerProvider;
import io.lettuce.core.tracing.Tracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Function;

/**
 * Lettuce 명령마다 OTel 클라이언트 스팬(db.system=redis)을 남기고, 지표는 감싼 추적기(Micrometer 관측 — lettuce_seconds)에 그대로 맡긴다.
 * Spring Boot 가 Lettuce 추적기를 Micrometer 관측으로 바꿔 끼워 OTel 에이전트의 Lettuce 계측이 빠지므로 스팬을 여기서 만든다.
 * 스팬은 이벤트 루프에서 시작하므로 부모는 명령을 보낸 스레드에서 담아 둔 OTel 맥락으로 잇는다. 에이전트가 없으면 스팬은 아무 일도 하지 않는다.
 */
final class RedisSpanTracing implements Tracing {

    static final String DB_SYSTEM = "redis";

    private final Tracing metrics;
    private final Function<String, SpanBuilder> spans;
    private final TraceContextProvider contexts;
    /** 명령마다 불리므로 한 번 만들어 둔다. */
    private final TracerProvider tracers;

    /** @param spans 스팬 이름 → OTel 스팬 빌더(Tracer::spanBuilder) */
    RedisSpanTracing(Tracing metrics, Function<String, SpanBuilder> spans) {
        this.metrics = metrics;
        this.spans = spans;
        this.contexts = new CapturingProvider(metrics.initialTraceContextProvider());
        Tracer tracer = new SpanTracer(metrics.getTracerProvider().getTracer());
        this.tracers = () -> tracer;
    }

    @Override
    public TracerProvider getTracerProvider() {
        return tracers;
    }

    @Override
    public TraceContextProvider initialTraceContextProvider() {
        return contexts;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean includeCommandArgsInSpanTags() {
        return metrics.includeCommandArgsInSpanTags();
    }

    @Override
    public Endpoint createEndpoint(SocketAddress socketAddress) {
        return new AddressEndpoint(socketAddress, metrics.createEndpoint(socketAddress));
    }

    /** 보낸 스레드에서 읽은 두 맥락 — 감싼 추적기의 것(없을 수 있다)과 OTel 의 것. */
    private record Captured(TraceContext metrics, Context otel) implements TraceContext {
    }

    private record AddressEndpoint(SocketAddress address, Endpoint metrics) implements Endpoint {
    }

    private record CapturingProvider(TraceContextProvider metrics) implements TraceContextProvider {

        @Override
        public TraceContext getTraceContext() {
            return new Captured(metrics.getTraceContext(), Context.current());
        }

        /** 만든 때가 아니라 구독할 때의 맥락을 담는다 — reactive 명령은 구독할 때 보낸다. */
        @Override
        public Mono<TraceContext> getTraceContextLater() {
            return Mono.defer(() -> {
                Context otel = Context.current();
                return metrics.getTraceContextLater()
                        .map(found -> (TraceContext) new Captured(found, otel))
                        .defaultIfEmpty(new Captured(null, otel));
            });
        }
    }

    private final class SpanTracer extends Tracer {

        private final Tracer metrics;

        SpanTracer(Tracer metrics) {
            this.metrics = metrics;
        }

        @Override
        public Tracer.Span nextSpan() {
            return new CommandSpan(metrics.nextSpan(), Context.current());
        }

        @Override
        public Tracer.Span nextSpan(TraceContext traceContext) {
            if (traceContext instanceof Captured captured) {
                return new CommandSpan(metrics.nextSpan(captured.metrics()), captured.otel());
            }
            return new CommandSpan(metrics.nextSpan(traceContext), Context.current());
        }
    }

    private final class CommandSpan extends Tracer.Span {

        private final Tracer.Span metrics;
        private final Context parent;
        private String name = DB_SYSTEM;
        private SocketAddress address;
        private Span span;

        CommandSpan(Tracer.Span metrics, Context parent) {
            this.metrics = metrics;
            this.parent = parent;
        }

        @Override
        public Tracer.Span start(RedisCommand<?, ?, ?> command) {
            SpanBuilder builder = spans.apply(name).setParent(parent).setSpanKind(SpanKind.CLIENT)
                    .setAttribute("db.system", DB_SYSTEM)
                    .setAttribute("db.operation", name);
            if (address instanceof InetSocketAddress inet) {
                builder.setAttribute("server.address", inet.getHostString())
                        .setAttribute("server.port", (long) inet.getPort());
            }
            span = builder.startSpan();
            if (command instanceof CompleteableCommand<?> completeable) {
                completeable.onComplete((output, throwable) -> end(command, throwable));
            }
            metrics.start(command);
            return this;
        }

        /** 오류 응답(WRONGTYPE 등)은 예외 없이 출력에 실려 온다. */
        private void end(RedisCommand<?, ?, ?> command, Throwable throwable) {
            String error = command.getOutput() == null ? null : command.getOutput().getError();
            if (error != null) {
                span.setStatus(StatusCode.ERROR, error);
            } else if (throwable != null) {
                span.recordException(throwable);
                span.setStatus(StatusCode.ERROR);
            }
            span.end();
        }

        @Override
        public Tracer.Span name(String name) {
            this.name = name;
            metrics.name(name);
            return this;
        }

        @Override
        public Tracer.Span annotate(String value) {
            metrics.annotate(value);
            return this;
        }

        @Override
        public Tracer.Span tag(String key, String value) {
            metrics.tag(key, value);
            return this;
        }

        @Override
        public Tracer.Span error(Throwable throwable) {
            metrics.error(throwable);
            return this;
        }

        @Override
        public Tracer.Span remoteEndpoint(Endpoint endpoint) {
            if (endpoint instanceof AddressEndpoint found) {
                address = found.address();
                metrics.remoteEndpoint(found.metrics());
            } else {
                metrics.remoteEndpoint(endpoint);
            }
            return this;
        }

        @Override
        public void finish() {
            metrics.finish();
        }
    }
}
