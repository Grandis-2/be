package com.grandis.nova.waitingroom.relay;

import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * 접수 POST 만 preorder 로 전달한다. 본문 · Idempotency-Key · 요청 ID · 액세스 토큰은 그대로 넘기고(preorder 가 다시
 * 검증한다), 대기열 안에서만 쓰는 대기 토큰은 뗀다.
 */
@Configuration(proxyBeanMethods = false)
class AcceptRoute {

    static final String ACCEPT_PATH = "/api/v1/preorders";
    private static final String QUEUE_TOKEN = "Queue-Token";
    /** 응답 꾸미기는 응답을 쓰는 필터보다 앞서 걸어야 본문을 본다. */
    private static final int WATCH_ORDER = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
    private static final int CHECK_ORDER = 0;

    @Bean
    RouteLocator acceptRoutes(RouteLocatorBuilder routes, RelayProperties properties, AdmissionTicketCheck check,
                              AcceptOutcomeWatch watch) {
        return routes.routes()
                .route("preorder-accept", route -> route.method(HttpMethod.POST).and().path(ACCEPT_PATH)
                        .filters(filters -> filters
                                .filter(watch, WATCH_ORDER)
                                .filter(check, CHECK_ORDER)
                                .removeRequestHeader(QUEUE_TOKEN))
                        .uri(properties.preorderUri()))
                .build();
    }
}
