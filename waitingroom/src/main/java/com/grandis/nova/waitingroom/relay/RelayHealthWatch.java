package com.grandis.nova.waitingroom.relay;

import com.grandis.nova.waitingroom.control.BrakeProperties;
import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.control.RelayOutcomeCounter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * preorder 로 실제 전달한 접수의 결과를 센다 — 5xx · 전달 실패 · 느린 응답이 나쁜 응답이다. 리더가 노드들의 합으로
 * 브레이크를 건다. 전달하지 않은 요청(400 · 403)과 빨리 끊은 요청은 세지 않고, 한 회원은 1초에 한 번만 센다.
 */
@Component
class RelayHealthWatch implements GatewayFilter {

    private final RelayOutcomeCounter outcomes;
    private final RedisClock clock;
    private final Duration slow;
    private final LongSupplier nanoTime;

    @Autowired
    RelayHealthWatch(RelayOutcomeCounter outcomes, RedisClock clock, BrakeProperties brake) {
        this(outcomes, clock, brake, System::nanoTime);
    }

    RelayHealthWatch(RelayOutcomeCounter outcomes, RedisClock clock, BrakeProperties brake, LongSupplier nanoTime) {
        this.outcomes = outcomes;
        this.clock = clock;
        this.slow = brake.slow();
        this.nanoTime = nanoTime;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long started = nanoTime.getAsLong();
        // preorder 응답이 도착해 쓰기 시작하는 때까지만 잰다 — 클라이언트가 천천히 받는 시간은 preorder 탓이 아니다
        AtomicLong committedAt = new AtomicLong(-1);
        exchange.getResponse().beforeCommit(() -> Mono.fromRunnable(() -> committedAt.compareAndSet(-1, nanoTime.getAsLong())));
        return chain.filter(exchange)
                .doFinally(signal -> record(exchange, signal, started, answeredAt(exchange, signal, committedAt)));
    }

    /**
     * preorder 가 답했으면 신호(끊김 · 쓰기 오류)와 상관없이 상태와 지연으로 판정한다. 답 전에 끊겼으면 slow 넘게
     * 기다렸을 때만, 답 전에 오류면(연결 실패 · 시한 · preorder 가 닫음) 언제나 나쁜 응답이다.
     */
    private void record(ServerWebExchange exchange, SignalType signal, long started, long answeredAt) {
        String customerId = exchange.getAttribute(AdmissionTicketCheck.CUSTOMER_ATTRIBUTE);
        if (customerId == null) {
            return;
        }
        boolean answered = answeredAt >= 0;
        boolean slowAnswer = Duration.ofNanos((answered ? answeredAt : nanoTime.getAsLong()) - started).compareTo(slow) >= 0;
        if (!answered && signal == SignalType.CANCEL) {
            if (slowAnswer) {
                outcomes.record(clock.now().getEpochSecond(), customerId, true);
            }
            return;
        }
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        boolean bad = (!answered && signal == SignalType.ON_ERROR) || status == null || status.is5xxServerError()
                || slowAnswer;
        outcomes.record(clock.now().getEpochSecond(), customerId, bad);
    }

    /**
     * 응답 관찰이 남긴 preorder 응답 시각이 있으면 그것을 쓴다. 없으면 응답을 쓰기 시작한 때를 쓰되, 오류로 끝났으면
     * 그 응답은 오류 처리기가 쓴 것이라 preorder 가 답한 것으로 보지 않는다.
     */
    private static long answeredAt(ServerWebExchange exchange, SignalType signal, AtomicLong committedAt) {
        Long upstream = exchange.getAttribute(AcceptOutcomeWatch.UPSTREAM_ANSWERED_AT);
        if (upstream != null) {
            return upstream;
        }
        return signal == SignalType.ON_ERROR ? -1 : committedAt.get();
    }
}
