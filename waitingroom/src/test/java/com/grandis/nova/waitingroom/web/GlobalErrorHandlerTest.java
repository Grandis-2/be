package com.grandis.nova.waitingroom.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.waitingroom.WaitingroomErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.client.PrematureCloseException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalErrorHandlerTest {

    private final GlobalErrorHandler handler =
            new GlobalErrorHandler(new ErrorResponses(JsonMapper.builder().build(), Clock.systemUTC()));

    @Test
    void 전달_대상에_붙지_못하면_503_과_짧은_재시도_간격이다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders"));

        handler.handle(exchange, new IllegalStateException("relay", new ConnectException("refused"))).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void 빈_연결을_기다리다_시한이_지나면_보내지_않은_것이라_503_이다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders"));

        handler.handle(exchange, new IllegalStateException("relay", new TimeoutException("pool"))).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void 보낸_뒤_응답_시한이_지나면_결과를_모르므로_503_이_아니라_504_다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders"));

        handler.handle(exchange, new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "응답 시한",
                new TimeoutException("response"))).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(504);
    }

    @ParameterizedTest
    @MethodSource("dropped")
    void 전달_대상과_붙은_뒤_답_전에_끊기면_500_이_아니라_다시_보내라는_503_이다(Throwable dropped) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, "preorder-accept");

        handler.handle(exchange, new IllegalStateException("relay", dropped)).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("DEPENDENCY_UNAVAILABLE");
    }

    static Stream<Throwable> dropped() {
        return Stream.of(PrematureCloseException.TEST_EXCEPTION,
                new SocketException("Connection reset"), new UnknownHostException("preorder"),
                new AbortedException("Connection has been closed BEFORE send operation"));
    }

    @Test
    void 전달_경로가_아닌_요청의_입출력_오류는_전달_대상_탓으로_돌리지_않는다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders/queue"));

        handler.handle(exchange, new IllegalStateException("queue", new IOException("broken"))).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(500);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("INTERNAL_ERROR");
    }

    @Test
    void 줄이_찼으면_429_와_긴_재시도_간격이다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders/queue"));

        handler.handle(exchange, new BusinessException(WaitingroomErrorCode.QUEUE_FULL)).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
    }
}
