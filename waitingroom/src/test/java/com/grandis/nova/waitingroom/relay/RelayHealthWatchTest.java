package com.grandis.nova.waitingroom.relay;

import com.grandis.nova.waitingroom.control.BrakeProperties;
import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.control.RelayOutcomeCounter;
import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RelayHealthWatchTest {

    static final Instant NOW = Instant.parse("2026-10-10T01:00:00Z");

    private final long[] nanos = {0};
    private final RelayOutcomeCounter outcomes = new RelayOutcomeCounter();
    private final RelayHealthWatch watch = new RelayHealthWatch(outcomes, new RedisClock(Clock.fixed(NOW, ZoneOffset.UTC)),
            new BrakeProperties(null, null, null, null, null, null, null, null, null, null, null, null), () -> nanos[0]);

    @Test
    void 전달한_접수만_세고_5xx_와_전달_실패는_나쁜_응답이다() {
        watch.filter(relayed("1"), exchange -> respond(exchange, HttpStatus.ACCEPTED)).block();
        watch.filter(relayed("2"), exchange -> respond(exchange, HttpStatus.CONFLICT)).block();
        watch.filter(relayed("3"), exchange -> respond(exchange, HttpStatus.SERVICE_UNAVAILABLE)).block();
        watch.filter(relayed("4"), exchange -> Mono.error(new IOException("closed"))).onErrorComplete().block();
        // 입장권 확인에서 막혀 전달하지 않은 요청
        watch.filter(MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders")),
                exchange -> respond(exchange, HttpStatus.FORBIDDEN)).block();

        assertThat(outcomes.totals()).isEqualTo(new RelayOutcome(4, 2));
    }

    @Test
    void 빨리_끊은_요청은_세지_않고_preorder_가_오래_답하지_않는_동안_끊긴_요청은_나쁘다() {
        watch.filter(relayed("1"), exchange -> Mono.never()).subscribe().dispose();
        assertThat(outcomes.totals()).isEqualTo(new RelayOutcome(0, 0));

        Disposable waiting = watch.filter(relayed("2"), exchange -> Mono.never()).subscribe();
        nanos[0] += Duration.ofSeconds(3).toNanos();
        waiting.dispose();
        assertThat(outcomes.totals()).as("preorder 가 멈추면 사용자가 새로고침해도 브레이크가 본다").isEqualTo(new RelayOutcome(1, 1));
    }

    @Test
    void preorder_가_답한_뒤_쓰다_끊긴_것은_상태와_지연으로만_판정한다() {
        watch.filter(relayed("1"), exchange -> {
            exchange.getAttributes().put(AcceptOutcomeWatch.UPSTREAM_ANSWERED_AT, nanos[0]);
            exchange.getResponse().setStatusCode(HttpStatus.ACCEPTED);
            return Mono.error(new AbortedException("client gone"));
        }).onErrorComplete().block();
        Disposable writing = watch.filter(relayed("2"), exchange -> {
            exchange.getAttributes().put(AcceptOutcomeWatch.UPSTREAM_ANSWERED_AT, nanos[0]);
            exchange.getResponse().setStatusCode(HttpStatus.ACCEPTED);
            return Mono.never();
        }).subscribe();
        writing.dispose();

        assertThat(outcomes.totals()).isEqualTo(new RelayOutcome(2, 0));
    }

    @Test
    void preorder_가_답하기_전의_오류는_종류와_상관없이_나쁘다() {
        watch.filter(relayed("1"), exchange -> Mono.error(new AbortedException("upstream closed"))).onErrorComplete().block();
        watch.filter(relayed("2"), exchange -> Mono.error(new ClosedChannelException())).onErrorComplete().block();

        assertThat(outcomes.totals()).isEqualTo(new RelayOutcome(2, 2));
    }

    @Test
    void 응답_관찰이_남긴_preorder_응답_시각이_있으면_그_뒤의_대기열_처리_시간은_빼고_잰다() {
        MockServerWebExchange exchange = relayed("1");
        watch.filter(exchange, current -> {
            current.getAttributes().put(AcceptOutcomeWatch.UPSTREAM_ANSWERED_AT, nanos[0]);
            nanos[0] += Duration.ofSeconds(3).toNanos();
            return respond(current, HttpStatus.CONFLICT);
        }).block();

        assertThat(outcomes.totals()).isEqualTo(new RelayOutcome(1, 0));
    }

    @Test
    void preorder_응답이_늦으면_나쁘고_응답_뒤_쓰기가_늦은_것은_세지_않는다() {
        watch.filter(relayed("1"), exchange -> {
            nanos[0] += Duration.ofSeconds(3).toNanos();
            return respond(exchange, HttpStatus.ACCEPTED);
        }).block();
        watch.filter(relayed("2"), exchange -> respond(exchange, HttpStatus.ACCEPTED)
                .doOnSuccess(done -> nanos[0] += Duration.ofSeconds(3).toNanos())).block();

        assertThat(outcomes.totals()).isEqualTo(new RelayOutcome(2, 1));
    }

    @Test
    void 한_회원은_1초에_한_번만_센다() {
        for (int i = 0; i < 100; i++) {
            watch.filter(relayed("1"), exchange -> Mono.error(new IOException("closed"))).onErrorComplete().block();
        }

        assertThat(outcomes.totals()).isEqualTo(new RelayOutcome(1, 1));
    }

    private static MockServerWebExchange relayed(String customerId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders"));
        exchange.getAttributes().put(AdmissionTicketCheck.PRODUCT_ATTRIBUTE, "101");
        exchange.getAttributes().put(AdmissionTicketCheck.CUSTOMER_ATTRIBUTE, customerId);
        return exchange;
    }

    private static Mono<Void> respond(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
