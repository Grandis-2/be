package com.grandis.nova.waitingroom.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청마다 추적 ID 를 정한다. 받은 X-Request-Id 가 형식에 맞으면 이어 쓰고, 아니면 새로 만든다.
 * 같은 값이 요청 헤더(뒷단 전달) · 응답 헤더 · 봉투 traceId · 로그 MDC 에 쓰인다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";

    /** Reactor 컨텍스트 · 로그 MDC 의 키. */
    public static final String TRACE_ID_KEY = "traceId";

    /** 교환 속성 키. 응답을 직접 쓰는 곳이 여기서 읽는다. */
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".traceId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolve(exchange.getRequest().getHeaders().get(HEADER));
        exchange.getAttributes().put(ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, requestId))
                .build();
        return chain.filter(exchange.mutate().request(request).build())
                .contextWrite(Context.of(TRACE_ID_KEY, requestId));
    }

    /** 이 요청의 추적 ID. 필터를 거치지 않은 교환이면 null 이다. */
    public static String traceId(ServerWebExchange exchange) {
        return exchange.getAttribute(ATTRIBUTE);
    }

    /** 한 줄이고 형식에 맞을 때만 이어 쓴다. 여러 줄이면 어느 값을 믿을지 모른다. */
    private static String resolve(List<String> values) {
        if (values != null && values.size() == 1 && VALID.matcher(values.getFirst()).matches()) {
            return values.getFirst();
        }
        return UUID.randomUUID().toString();
    }
}
