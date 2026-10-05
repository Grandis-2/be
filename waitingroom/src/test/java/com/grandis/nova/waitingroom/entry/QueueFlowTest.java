package com.grandis.nova.waitingroom.entry;

import com.grandis.nova.waitingroom.control.SnapshotHolder;
import com.grandis.nova.waitingroom.control.TestSnapshots;
import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.redis.ControlStore;
import com.grandis.nova.waitingroom.redis.LuaScripts;
import com.grandis.nova.waitingroom.support.RedisContainer;
import com.grandis.nova.waitingroom.support.TestJwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 진입 · 조회 · 접수 전달을 실제 Redis 와 가짜 preorder 로 끝까지 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"waitingroom.control.enabled=false", "jwt.jwk-set-uri=",
                "waitingroom.token.secret=" + TestJwts.TOKEN_SECRET})
class QueueFlowTest {

    static final String PRODUCT = "101";
    static final Duration WAIT = Duration.ofSeconds(5);

    /** 가짜 preorder — 다음 응답을 정해 두고, 받은 요청 헤더를 남긴다. */
    static final AtomicReference<FakeReply> NEXT = new AtomicReference<>();
    static final AtomicReference<Map<String, String>> RECEIVED = new AtomicReference<>();
    static final DisposableServer PREORDER = HttpServer.create().port(0)
            .route(routes -> routes.post("/api/v1/preorders", (request, response) -> {
                Map<String, String> headers = new HashMap<>();
                request.requestHeaders().forEach(header -> headers.put(header.getKey().toLowerCase(), header.getValue()));
                RECEIVED.set(headers);
                FakeReply reply = NEXT.get();
                return request.receive().then()
                        .then(response.status(reply.status()).header("Content-Type", "application/json")
                                .sendString(Mono.just(reply.body())).then());
            }))
            .bindNow();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.issuer", () -> TestJwts.ISSUER);
        registry.add("jwt.public-keys." + TestJwts.KID, TestJwts::publicKeyPem);
        registry.add("spring.data.redis.host", RedisContainer::host);
        registry.add("spring.data.redis.port", RedisContainer::port);
        registry.add("waitingroom.relay.preorder-uri", () -> "http://localhost:" + PREORDER.port());
    }

    @AfterAll
    static void stopPreorder() {
        PREORDER.disposeNow();
    }

    @LocalServerPort
    int port;

    /** 그룹 구성(readiness · liveness)을 실제 기본값으로 본다. 관리 포트 보안은 고정 포트 구성에서만 열린다. */
    @Autowired
    HealthEndpoint health;

    @Autowired
    SnapshotHolder snapshots;

    @Autowired
    AdmissionTicket tickets;

    WebTestClient client;
    ControlStore control;
    Instant now;
    long round;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        ReactiveStringRedisTemplate redis = RedisContainer.fresh();
        control = new ControlStore(new LuaScripts(redis), redis);
        now = Instant.now();
        NEXT.set(new FakeReply(202, "{\"success\":true}"));
        RECEIVED.set(null);
    }

    private void snapshot(ProductState state) {
        snapshot(state, MaxWait.unlimited());
    }

    private void snapshot(ProductState state, MaxWait maxWait) {
        TestSnapshots.put(snapshots, Map.of(PRODUCT, state), new SnapshotMeta(100, 1, maxWait));
    }

    private SalesWindow open() {
        return new SalesWindow(now.minusSeconds(60), now.plusSeconds(3_600));
    }

    private ProductState crowded() {
        return ProductState.withQueue(5, 1, open(), ProductState.UNLIMITED_CAP);
    }

    private WebTestClient.ResponseSpec enter(String customer) {
        return client.post().uri("/api/v1/preorders/queue?productId=" + PRODUCT)
                .headers(headers -> headers.setBearerAuth(TestJwts.user(customer, now)))
                .exchange();
    }

    private WebTestClient.ResponseSpec status(String customer, String queueToken) {
        return client.get().uri("/api/v1/preorders/queue?productId=" + PRODUCT)
                .headers(headers -> {
                    headers.setBearerAuth(TestJwts.user(customer, now));
                    if (queueToken != null) {
                        headers.set("Queue-Token", queueToken);
                    }
                })
                .exchange();
    }

    private String queueToken(String customer) {
        return (String) data(enter(customer).expectStatus().isAccepted()).get("queueToken");
    }

    private static String ticketOf(WebTestClient.ResponseSpec response) {
        return (String) data(response.expectStatus().is2xxSuccessful()).get("admissionTicket");
    }

    private static Map<?, ?> data(WebTestClient.ResponseSpec response) {
        Map<?, ?> body = response.expectBody(Map.class).returnResult().getResponseBody();
        return (Map<?, ?>) body.get("data");
    }

    private void admit(long count) {
        control.apply(PRODUCT, count, 1, 60_000, -1, ++round).block(WAIT);
    }

    @Nested
    class 진입 {

        @Test
        void 한산하면_줄_없이_바로_입장권을_준다() {
            // 다른 시험이 방금 줄에 세운 모델은 이 노드가 몇 초간 줄로 보낸다(래치) — 아무도 줄 서지 않은 모델로 본다
            String quiet = "301";
            TestSnapshots.put(snapshots, Map.of(quiet, ProductState.idle(open(), ProductState.UNLIMITED_CAP)),
                    new SnapshotMeta(100, 1, MaxWait.unlimited()));

            Map<?, ?> admitted = data(client.post().uri("/api/v1/preorders/queue?productId=" + quiet)
                    .headers(headers -> headers.setBearerAuth(TestJwts.user("7", now))).exchange()
                    .expectStatus().isOk());

            assertThat(admitted.get("status")).isEqualTo("ADMITTED");
            assertThat((Integer) admitted.get("expiresIn")).isBetween(1, (int) AdmissionTicket.TTL_SEC);
            assertThat(tickets.verify((String) admitted.get("admissionTicket"), quiet, now)).contains("7");
        }

        @Test
        void 몰리면_줄에_세우고_202_대기_토큰과_순서와_다시_올_때를_준다() {
            snapshot(crowded());

            enter("1").expectStatus().isAccepted()
                    .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                    .expectBody()
                    .jsonPath("$.data.status").isEqualTo("WAITING")
                    .jsonPath("$.data.position").isEqualTo(1)
                    .jsonPath("$.data.queueToken").isNotEmpty()
                    .jsonPath("$.data.rejoined").isEqualTo(false);
            enter("2").expectStatus().isAccepted().expectBody().jsonPath("$.data.position").isEqualTo(2);
            enter("1").expectStatus().isAccepted().expectBody()
                    .jsonPath("$.data.position").isEqualTo(1)
                    .jsonPath("$.data.rejoined").isEqualTo(true);
        }

        @Test
        void 같은_회원이_동시에_여러_번_진입해도_한_자리만_잡고_같은_순서와_대기_토큰을_받는다() {
            snapshot(crowded());

            List<Map<?, ?>> views = Flux.range(0, 8)
                    .flatMap(i -> Mono.<Map<?, ?>>fromCallable(() -> data(enter("1").expectStatus().isAccepted()))
                            .subscribeOn(Schedulers.boundedElastic()), 8)
                    .collectList().block(WAIT);
            String token = (String) views.getFirst().get("queueToken");

            assertThat(views.stream().<Object>map(view -> view.get("position")).distinct().toList()).containsExactly(1);
            assertThat(views.stream().<Object>map(view -> view.get("queueToken")).distinct().toList()).containsExactly(token);
            status("1", token).expectBody().jsonPath("$.data.totalWaiting").isEqualTo(1);
        }

        @Test
        void 입장한_사람이_다시_진입하면_조회와_같은_입장권을_받는다() {
            snapshot(crowded());
            String token = queueToken("1");
            admit(1);

            String fromStatus = ticketOf(status("1", token));
            String fromEntry = ticketOf(enter("1"));

            assertThat(fromEntry).isEqualTo(fromStatus);
        }

        @Test
        void 오픈_전_마감_모르는_상품_잘못된_productId_는_각자의_코드로_거절한다() {
            snapshot(ProductState.idle(new SalesWindow(now.plusSeconds(600), now.plusSeconds(3_600)),
                    ProductState.UNLIMITED_CAP));
            enter("1").expectStatus().isEqualTo(409).expectBody()
                    .jsonPath("$.error.code").isEqualTo("SALE_NOT_OPEN")
                    .jsonPath("$.error.details.reason").value(reason -> assertThat((String) reason).startsWith("opensAt="));

            snapshot(ProductState.idle(new SalesWindow(now.minusSeconds(600), now.minusSeconds(1)),
                    ProductState.UNLIMITED_CAP));
            enter("1").expectStatus().isEqualTo(409).expectBody()
                    .jsonPath("$.error.code").isEqualTo("SALE_CLOSED")
                    .jsonPath("$.error.details.reason").value(reason -> assertThat((String) reason).startsWith("closesAt="));

            client.post().uri("/api/v1/preorders/queue?productId=999")
                    .headers(headers -> headers.setBearerAuth(TestJwts.user("1", now))).exchange()
                    .expectStatus().isNotFound().expectBody().jsonPath("$.error.code").isEqualTo("PRODUCT_NOT_FOUND");
            client.post().uri("/api/v1/preorders/queue?productId={id}", "0101")
                    .headers(headers -> headers.setBearerAuth(TestJwts.user("1", now))).exchange()
                    .expectStatus().isBadRequest().expectBody().jsonPath("$.error.code").isEqualTo("VALIDATION_FAILED");
        }

        @Test
        void 줄이_받아_줄_길이를_넘으면_429_와_다시_올_때를_준다() {
            snapshot(ProductState.withQueue(1, 5, open(), ProductState.UNLIMITED_CAP), MaxWait.of(Duration.ofSeconds(5)));

            enter("1").expectStatus().isEqualTo(429)
                    .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                    .expectBody().jsonPath("$.error.code").isEqualTo("QUEUE_FULL");
        }

        @Test
        void 줄이_찼어도_이미_줄에_선_사람과_입장한_사람은_자리와_입장권을_그대로_받는다() {
            snapshot(crowded());
            String token = queueToken("1");
            queueToken("2");
            admit(1);
            String ticket = ticketOf(status("1", token));
            snapshot(ProductState.withQueue(1, 5, open(), ProductState.UNLIMITED_CAP), MaxWait.of(Duration.ofSeconds(5)));

            assertThat(ticketOf(enter("1"))).isEqualTo(ticket);
            enter("2").expectStatus().isAccepted().expectBody()
                    .jsonPath("$.data.position").isEqualTo(1)
                    .jsonPath("$.data.rejoined").isEqualTo(true);
            enter("3").expectStatus().isEqualTo(429);
        }

        @Test
        void 관리자_토큰으로는_진입하지_못한다() {
            snapshot(ProductState.idle(open(), ProductState.UNLIMITED_CAP));

            client.post().uri("/api/v1/preorders/queue?productId=" + PRODUCT)
                    .headers(headers -> headers.setBearerAuth(TestJwts.admin(now))).exchange()
                    .expectStatus().isForbidden();
        }
    }

    @Nested
    class 조회 {

        @Test
        void 줄_서는_중이면_순서_총원_뒤_인원과_다시_올_때를_준다() {
            snapshot(crowded());
            String token = queueToken("1");
            queueToken("2");

            status("1", token).expectStatus().isOk()
                    .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                    .expectBody()
                    .jsonPath("$.data.status").isEqualTo("WAITING")
                    .jsonPath("$.data.position").isEqualTo(1)
                    .jsonPath("$.data.totalWaiting").isEqualTo(2)
                    .jsonPath("$.data.behind").isEqualTo(1)
                    .jsonPath("$.data.queueToken").doesNotExist();
        }

        @Test
        void 차례가_오면_입장권을_주고_다시_물어도_같은_입장권이다() {
            snapshot(crowded());
            String token = queueToken("1");
            admit(1);

            String first = ticketOf(status("1", token));
            String again = ticketOf(status("1", token));

            assertThat(first).isEqualTo(again);
            assertThat(tickets.verify(first, PRODUCT, now)).contains("1");
        }

        @Test
        void 대기_토큰이_없으면_400_남의_것이면_줄에_없다고만_답한다() {
            snapshot(crowded());
            String token = queueToken("1");

            status("1", null).expectStatus().isBadRequest().expectBody()
                    .jsonPath("$.error.code").isEqualTo("VALIDATION_FAILED");
            status("2", token).expectStatus().isOk().expectBody()
                    .jsonPath("$.data.status").isEqualTo("CLOSED")
                    .jsonPath("$.data.reason").isEqualTo("NOT_IN_QUEUE");
        }

        @Test
        void 마감되면_SALE_CLOSED_로_닫는다() {
            snapshot(crowded());
            String token = queueToken("1");
            snapshot(ProductState.closed(1, new SalesWindow(now.minusSeconds(600), now.minusSeconds(1)),
                    ProductState.UNLIMITED_CAP));

            status("1", token).expectStatus().isOk().expectBody()
                    .jsonPath("$.data.status").isEqualTo("CLOSED")
                    .jsonPath("$.data.reason").isEqualTo("SALE_CLOSED");
        }
    }

    @Nested
    class 접수_전달 {

        private WebTestClient.ResponseSpec accept(String customer, String ticket) {
            return client.post().uri("/api/v1/preorders?productId=" + PRODUCT)
                    .headers(headers -> {
                        headers.setBearerAuth(TestJwts.user(customer, now));
                        headers.set("Idempotency-Key", "key-0123456789");
                        headers.set("Queue-Token", "qt_internal");
                        if (ticket != null) {
                            headers.set("X-Admission-Ticket", ticket);
                        }
                    })
                    .bodyValue(Map.of("productId", 101, "optionId", 1))
                    .exchange();
        }

        @Test
        void 입장권이_맞으면_그대로_전달하고_대기_토큰만_뗀다() {
            String ticket = tickets.issue(PRODUCT, "1", now);

            accept("1", ticket).expectStatus().isAccepted().expectBody().jsonPath("$.success").isEqualTo(true);

            Map<String, String> received = RECEIVED.get();
            assertThat(received).containsEntry("x-admission-ticket", ticket)
                    .containsEntry("idempotency-key", "key-0123456789")
                    .containsKey("x-request-id")
                    .doesNotContainKey("queue-token");
            assertThat(received.get("authorization")).startsWith("Bearer ");
        }

        @Test
        void 만료된_지_잠깐인_입장권은_전달해_같은_접수의_재전송을_preorder_가_판단하게_한다() {
            String expired = tickets.issue(PRODUCT, "1", now.minusSeconds(600));

            accept("1", expired).expectStatus().isAccepted();

            assertThat(RECEIVED.get()).isNotNull();
        }

        @Test
        void 만료된_지_오래된_입장권은_전달하지_않는다() {
            String old = tickets.issue(PRODUCT, "1", now.minus(Duration.ofHours(1)));

            accept("1", old).expectStatus().isForbidden().expectBody()
                    .jsonPath("$.error.code").isEqualTo("ADMISSION_TICKET_INVALID");

            assertThat(RECEIVED.get()).isNull();
        }

        @Test
        void preorder_가_이미_쓴_입장권이라고_답해도_입장_기록을_지운다() {
            snapshot(crowded());
            String token = queueToken("1");
            admit(1);
            String ticket = ticketOf(status("1", token));
            NEXT.set(new FakeReply(409, "{\"success\":false,\"error\":{\"code\":\"ADMISSION_TICKET_USED\"}}"));

            accept("1", ticket).expectStatus().isEqualTo(409)
                    .expectHeader().value(HttpHeaders.RETRY_AFTER, seconds -> assertThat(Long.parseLong(seconds)).isBetween(31L, 60L));

            status("1", token).expectStatus().isOk().expectBody().jsonPath("$.data.reason").isEqualTo("NOT_IN_QUEUE");
        }

        @Test
        void 다른_탭의_옛_입장권이_거절돼도_지금_입장은_지우지_않는다() {
            snapshot(crowded());
            String token = queueToken("1");
            admit(1);
            String current = ticketOf(status("1", token));
            String oldTab = tickets.issue(PRODUCT, "1", now.minusSeconds(300));
            NEXT.set(new FakeReply(403, "{\"success\":false,\"error\":{\"code\":\"ADMISSION_TICKET_STALE\"}}"));

            accept("1", oldTab).expectStatus().isForbidden();

            assertThat(ticketOf(status("1", token))).isEqualTo(current);
        }

        @Test
        void 입장권이_없으면_400_남의_것이면_403_이고_전달하지_않는다() {
            accept("1", null).expectStatus().isBadRequest().expectBody()
                    .jsonPath("$.error.code").isEqualTo("ADMISSION_TICKET_REQUIRED");
            accept("2", tickets.issue(PRODUCT, "1", now)).expectStatus().isForbidden().expectBody()
                    .jsonPath("$.error.code").isEqualTo("ADMISSION_TICKET_INVALID");

            assertThat(RECEIVED.get()).isNull();
        }

        @Test
        void preorder_가_마감이라고_답하면_응답은_그대로_주고_이_노드는_진입을_닫는다() {
            snapshot(ProductState.idle(open(), ProductState.UNLIMITED_CAP));
            String body = "{\"success\":false,\"error\":{\"code\":\"SALE_CLOSED\",\"message\":\"마감\"}}";
            NEXT.set(new FakeReply(409, body));

            String relayed = accept("1", tickets.issue(PRODUCT, "1", now)).expectStatus().isEqualTo(409)
                    .expectBody(String.class).returnResult().getResponseBody();

            assertThat(relayed).isEqualTo(body);
            enter("2").expectStatus().isEqualTo(409).expectBody().jsonPath("$.error.code").isEqualTo("SALE_CLOSED");
        }

        @Test
        void preorder_가_오래된_입장권이라고_답하면_입장_기록을_지워_다음_진입이_새로_판정된다() {
            snapshot(crowded());
            String token = queueToken("1");
            admit(1);
            String ticket = ticketOf(status("1", token));
            NEXT.set(new FakeReply(403, "{\"success\":false,\"error\":{\"code\":\"ADMISSION_TICKET_STALE\"}}"));

            accept("1", ticket).expectStatus().isForbidden();

            status("1", token).expectStatus().isOk().expectBody()
                    .jsonPath("$.data.status").isEqualTo("CLOSED")
                    .jsonPath("$.data.reason").isEqualTo("NOT_IN_QUEUE");
            enter("1").expectStatus().isAccepted().expectBody().jsonPath("$.data.status").isEqualTo("WAITING");
        }
    }

    @Nested
    class 헬스 {

        @Test
        void 판정_재료를_받은_노드는_준비됐고_제어_평면을_끈_구성은_살아_있다() {
            snapshot(crowded());

            assertThat(health.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
            assertThat(health.healthForPath("liveness").getStatus()).isEqualTo(Status.UP);
        }
    }

    record FakeReply(int status, String body) {
    }
}
