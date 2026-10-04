package com.grandis.nova.waitingroom.relay;

import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.ErrorCode;
import com.grandis.nova.waitingroom.WaitingroomErrorCode;
import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.domain.queue.SignedToken;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import com.grandis.nova.waitingroom.web.ErrorResponses;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 접수 전 입장권 확인 — 없으면 400, 서명 · 모델 · 회원이 어긋나면 403. 판정도 줄 서기도 하지 않는다.
 * 만료된 지 잠깐인 입장권은 넘긴다(응답을 잃은 같은 접수의 재전송이면 preorder 가 기존 예약을 돌려준다).
 */
@Component
class AdmissionTicketCheck implements GatewayFilter {

    static final String HEADER = "X-Admission-Ticket";
    static final String PRODUCT_ID = "productId";
    /** 접수 응답을 볼 때 쓰는 교환 속성. */
    static final String PRODUCT_ATTRIBUTE = AdmissionTicketCheck.class.getName() + ".product";
    static final String CUSTOMER_ATTRIBUTE = AdmissionTicketCheck.class.getName() + ".customer";
    static final String EXPIRES_ATTRIBUTE = AdmissionTicketCheck.class.getName() + ".expires";
    /** 만료 뒤 재전송을 받아 주는 기간. 기한이 없으면 입장권 한 장으로 대기열을 거치지 않는 길이 계속 열린다. */
    static final Duration REPLAY_GRACE = Duration.ofMinutes(10);

    private final AdmissionTicket tickets;
    private final RedisClock clock;
    private final ErrorResponses errorResponses;
    private final RelayMetrics metrics;

    AdmissionTicketCheck(AdmissionTicket tickets, RedisClock clock, ErrorResponses errorResponses, RelayMetrics metrics) {
        this.tickets = tickets;
        this.clock = clock;
        this.errorResponses = errorResponses;
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String productId = exchange.getRequest().getQueryParams().getFirst(PRODUCT_ID);
        if (!RedisKeys.validProductKey(productId)) {
            metrics.checked(CommonErrorCode.VALIDATION_FAILED.name());
            return errorResponses.write(exchange, CommonErrorCode.VALIDATION_FAILED, "productId 가 올바르지 않습니다.",
                    Map.of("field", PRODUCT_ID));
        }
        String ticket = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (ticket == null || ticket.isBlank()) {
            return reject(exchange, WaitingroomErrorCode.ADMISSION_TICKET_REQUIRED);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getName)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(customer -> {
                    Instant now = clock.now();
                    Optional<SignedToken.Holder> holder = tickets.authenticate(ticket, productId, now)
                            .filter(found -> customer.isPresent() && found.customerId().equals(customer.get()))
                            .filter(found -> now.isBefore(found.expiresAt().plus(REPLAY_GRACE)));
                    if (holder.isEmpty()) {
                        return reject(exchange, WaitingroomErrorCode.ADMISSION_TICKET_INVALID);
                    }
                    metrics.checked(holder.get().expired(now) ? "FORWARDED_EXPIRED" : "FORWARDED");
                    exchange.getAttributes().put(PRODUCT_ATTRIBUTE, productId);
                    exchange.getAttributes().put(CUSTOMER_ATTRIBUTE, holder.get().customerId());
                    exchange.getAttributes().put(EXPIRES_ATTRIBUTE, holder.get().expiresAt());
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode code) {
        metrics.checked(code.name());
        return errorResponses.write(exchange, code);
    }
}
