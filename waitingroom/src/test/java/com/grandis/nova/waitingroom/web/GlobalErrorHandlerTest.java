package com.grandis.nova.waitingroom.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.waitingroom.WaitingroomErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.time.Clock;

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
    void 줄이_찼으면_429_와_긴_재시도_간격이다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders/queue"));

        handler.handle(exchange, new BusinessException(WaitingroomErrorCode.QUEUE_FULL)).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
    }
}
