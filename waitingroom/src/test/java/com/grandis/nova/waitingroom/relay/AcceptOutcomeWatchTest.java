package com.grandis.nova.waitingroom.relay;

import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.control.TestSnapshots;
import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.entry.ProductClosures;
import com.grandis.nova.waitingroom.redis.LuaScripts;
import com.grandis.nova.waitingroom.redis.QueueStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptOutcomeWatchTest {

    static final String USED = "{\"success\":false,\"error\":{\"code\":\"ADMISSION_TICKET_USED\"}}";

    @Test
    void 다시_진입할_때는_지금_더하기_오차_뒤에_시작하는_창이라_새_입장권이_접수_규칙을_통과한다() {
        for (long now = 1_000; now < 1_000 + 2 * AdmissionTicket.WINDOW_SEC; now++) {
            long wait = AcceptOutcomeWatch.retryAfterSeconds(Instant.ofEpochSecond(now));
            long windowStart = (now + wait) / AdmissionTicket.WINDOW_SEC * AdmissionTicket.WINDOW_SEC;

            assertThat(windowStart).as("now=%d", now).isGreaterThan(now + AdmissionTicket.WINDOW_SEC);
            assertThat(wait).isBetween(1L, 2 * AdmissionTicket.WINDOW_SEC);
        }
    }

    /** 입장 기록을 지우지 못해도 접수 응답은 그대로 나가고, 실패를 세고, 다음 성공은 다시 센다. */
    @Nested
    class 입장_기록_삭제_실패 {

        private final Deque<Supplier<Mono<Boolean>>> replies = new ArrayDeque<>();
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final QueueStore queue = new QueueStore(new LuaScripts(null)) {
            @Override
            public Mono<Boolean> forgetAdmission(String productKey, String customerId, Instant ticketExpiresAt) {
                return replies.removeFirst().get();
            }
        };
        private final AcceptOutcomeWatch watch = new AcceptOutcomeWatch(TestSnapshots.emptyHolder(),
                new RedisClock(Clock.systemUTC()), new ProductClosures(), queue, JsonMapper.builder().build(),
                new RelayMetrics(registry));

        @Test
        void 응답을_쓰기_시작하면_preorder_가_답한_시각을_남긴다() {
            replies.add(() -> Mono.just(true));
            MockServerWebExchange exchange = rejectedAccept();

            watch.filter(exchange, AcceptOutcomeWatchTest::preorderSaysUsed).block(Duration.ofSeconds(5));

            assertThat(exchange.<Long>getAttribute(AcceptOutcomeWatch.UPSTREAM_ANSWERED_AT)).isNotNull();
        }

        @Test
        void Redis_오류여도_응답과_Retry_After_는_그대로_나가고_실패를_센다() {
            replies.add(() -> Mono.error(new RedisConnectionFailureException("down")));
            MockServerWebExchange exchange = rejectedAccept();

            watch.filter(exchange, AcceptOutcomeWatchTest::preorderSaysUsed).block(Duration.ofSeconds(5));

            assertRelayed(exchange);
            assertThat(failures()).isEqualTo(1);
        }

        @Test
        void 시한_안에_답이_없어도_시한에서_끊고_응답을_돌려준다() {
            replies.add(Mono::never);

            StepVerifier.withVirtualTime(() -> {
                        MockServerWebExchange exchange = rejectedAccept();
                        return watch.filter(exchange, AcceptOutcomeWatchTest::preorderSaysUsed).thenReturn(exchange);
                    })
                    .expectSubscription()
                    .expectNoEvent(Duration.ofMillis(499))
                    .thenAwait(Duration.ofMillis(1))
                    .assertNext(this::assertRelayed)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
            assertThat(failures()).isEqualTo(1);
        }

        @Test
        void 실패_뒤_성공하면_지운_것을_다시_센다() {
            replies.add(() -> Mono.error(new RedisConnectionFailureException("down")));
            replies.add(() -> Mono.just(true));

            watch.filter(rejectedAccept(), AcceptOutcomeWatchTest::preorderSaysUsed).block(Duration.ofSeconds(5));
            watch.filter(rejectedAccept(), AcceptOutcomeWatchTest::preorderSaysUsed).block(Duration.ofSeconds(5));

            assertThat(failures()).isEqualTo(1);
            assertThat(registry.get("waitingroom.relay.observed").tag("code", "ADMISSION_TICKET_USED").counter().count())
                    .isEqualTo(1);
        }

        private void assertRelayed(MockServerWebExchange exchange) {
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(USED);
            assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        }

        private double failures() {
            return registry.get("waitingroom.relay.forget.failures").counter().count();
        }
    }

    private static MockServerWebExchange rejectedAccept() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders"));
        exchange.getAttributes().put(AdmissionTicketCheck.PRODUCT_ATTRIBUTE, "101");
        exchange.getAttributes().put(AdmissionTicketCheck.CUSTOMER_ATTRIBUTE, "1");
        exchange.getAttributes().put(AdmissionTicketCheck.EXPIRES_ATTRIBUTE, Instant.now());
        return exchange;
    }

    /** 가짜 preorder — 409 USED 를 쓴다. 감시 필터가 감싼 응답으로 쓰므로 본문이 관찰을 거친다. */
    private static Mono<Void> preorderSaysUsed(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.CONFLICT);
        byte[] body = USED.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
