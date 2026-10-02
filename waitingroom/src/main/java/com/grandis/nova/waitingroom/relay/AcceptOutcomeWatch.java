package com.grandis.nova.waitingroom.relay;

import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.control.SnapshotHolder;
import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.entry.ProductClosures;
import com.grandis.nova.waitingroom.redis.QueueStore;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 접수 응답에서 대기열이 따를 것을 본다. 409 SALE_CLOSED 면 이 노드가 그 모델 진입을 바로 닫고, 입장권을 더 못 쓴다는
 * 답(STALE · USED)이면 그 입장권을 낸 입장 기록을 지워 다음 진입이 새로 판정되게 한다. 응답은 그대로 돌려준다.
 */
@Component
class AcceptOutcomeWatch implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(AcceptOutcomeWatch.class);

    static final String SALE_CLOSED = "SALE_CLOSED";
    static final String TICKET_STALE = "ADMISSION_TICKET_STALE";
    static final String TICKET_USED = "ADMISSION_TICKET_USED";
    /** 본문을 읽는 것은 이 상태들뿐이다. 나머지 응답은 버퍼에 담지 않고 흘려보낸다. */
    private static final Set<Integer> WATCHED = Set.of(403, 409);
    /** 오류 본문은 수백 바이트다. 길이를 밝힌 본문이 이보다 크면 읽지 않고 그대로 흘려보낸다. */
    private static final int MAX_ERROR_BODY = 256 * 1024;
    private static final Duration FORGET_TIMEOUT = Duration.ofMillis(500);

    private final SnapshotHolder snapshots;
    private final RedisClock clock;
    private final ProductClosures closures;
    private final QueueStore queue;
    private final JsonMapper jsonMapper;
    private final RelayMetrics metrics;
    /** 장애 동안 요청마다 쌓이지 않게 상태가 바뀔 때만 로그를 남긴다. */
    private final AtomicBoolean forgetFailing = new AtomicBoolean();

    AcceptOutcomeWatch(SnapshotHolder snapshots, RedisClock clock, ProductClosures closures, QueueStore queue,
                       JsonMapper jsonMapper, RelayMetrics metrics) {
        this.snapshots = snapshots;
        this.clock = clock;
        this.closures = closures;
        this.queue = queue;
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponseDecorator watched = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                HttpStatusCode status = getStatusCode();
                if (status == null || !WATCHED.contains(status.value())
                        || getHeaders().containsHeader(HttpHeaders.CONTENT_ENCODING)
                        || getHeaders().getContentLength() > MAX_ERROR_BODY) {
                    return super.writeWith(body);
                }
                return DataBufferUtils.join(body, MAX_ERROR_BODY)
                        .map(AcceptOutcomeWatch::drain)
                        .defaultIfEmpty(new byte[0])
                        .flatMap(bytes -> observe(exchange, bytes)
                                .then(super.writeWith(Mono.fromSupplier(() -> bufferFactory().wrap(bytes)))));
            }
        };
        return chain.filter(exchange.mutate().response(watched).build());
    }

    private Mono<Void> observe(ServerWebExchange exchange, byte[] body) {
        String productKey = exchange.getAttribute(AdmissionTicketCheck.PRODUCT_ATTRIBUTE);
        String customerId = exchange.getAttribute(AdmissionTicketCheck.CUSTOMER_ATTRIBUTE);
        Instant ticketExpiresAt = exchange.getAttribute(AdmissionTicketCheck.EXPIRES_ATTRIBUTE);
        if (productKey == null || customerId == null || ticketExpiresAt == null) {
            return Mono.empty();
        }
        String code = errorCode(body);
        return switch (code) {
            case SALE_CLOSED -> Mono.fromRunnable(() -> snapshots.current().flatMap(snapshot -> snapshot.product(productKey))
                    .ifPresent(state -> {
                        closures.observeClosed(productKey, state.window());
                        metrics.observed(SALE_CLOSED);
                    }));
            case TICKET_STALE, TICKET_USED -> Mono.fromRunnable(() -> exchange.getResponse().getHeaders()
                            .set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(clock.now()))))
                    .then(queue.forgetAdmission(productKey, customerId, ticketExpiresAt))
                    .timeout(FORGET_TIMEOUT)
                    .doOnNext(removed -> {
                        if (forgetFailing.compareAndSet(true, false)) {
                            log.info("입장 기록 지우기 회복");
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .doOnNext(removed -> metrics.observed(code))
                    // 지우지 못해도 응답은 돌려준다. 남은 입장 기록은 입장권 수명이 지나면 끝난 것으로 본다
                    .onErrorResume(e -> {
                        metrics.forgetFailed();
                        if (forgetFailing.compareAndSet(false, true)) {
                            log.warn("입장 기록을 지우지 못했다 — 입장권 수명이 지나면 풀린다: {}", e.toString());
                        }
                        return Mono.empty();
                    })
                    .then();
            default -> Mono.empty();
        };
    }

    /**
     * 다시 진입할 때까지. 같은 30초 창에서는 같은 입장권이 나오고, 접수 쪽은 마지막 접수 뒤(오차 30초) 창의 입장권만
     * 받으므로 지금 + 오차 이후에 시작하는 창까지 기다리게 한다(최대 약 60초).
     */
    static long retryAfterSeconds(Instant now) {
        long window = AdmissionTicket.WINDOW_SEC;
        long acceptableFrom = (now.getEpochSecond() + window) / window * window + window;
        return acceptableFrom - now.getEpochSecond();
    }

    private String errorCode(byte[] body) {
        try {
            JsonNode code = jsonMapper.readTree(body).path("error").path("code");
            return code.isString() ? code.asString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static byte[] drain(DataBuffer joined) {
        try {
            byte[] bytes = new byte[joined.readableByteCount()];
            joined.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(joined);
        }
    }
}
