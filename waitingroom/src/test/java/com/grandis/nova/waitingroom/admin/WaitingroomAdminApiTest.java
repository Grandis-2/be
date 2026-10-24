package com.grandis.nova.waitingroom.admin;

import com.grandis.nova.waitingroom.control.CapacityStatus;
import com.grandis.nova.waitingroom.control.SnapshotHolder;
import com.grandis.nova.waitingroom.control.TestSnapshots;
import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import com.grandis.nova.waitingroom.redis.ControlStore.RelayOutcome;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import com.grandis.nova.waitingroom.support.RedisContainer;
import com.grandis.nova.waitingroom.support.TestJwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.grandis.nova.waitingroom.support.TestIds.customerId;
import static com.grandis.nova.waitingroom.support.TestIds.productKey;
import static org.assertj.core.api.Assertions.assertThat;

/** 운영값 관리자 API 를 실제 Redis 로 확인한다. 리더 반영은 제어 평면 시험이 본다. */
@SpringBootTest(properties = {"waitingroom.control.enabled=false", "jwt.jwk-set-uri=",
        "waitingroom.token.secret=" + TestJwts.TOKEN_SECRET})
class WaitingroomAdminApiTest {

    static final String BASE = "/api/v1/admin/waitingroom";
    static final Duration WAIT = Duration.ofSeconds(5);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.issuer", () -> TestJwts.ISSUER);
        registry.add("jwt.public-keys." + TestJwts.KID, TestJwts::publicKeyPem);
        registry.add("spring.data.redis.host", RedisContainer::host);
        registry.add("spring.data.redis.port", RedisContainer::port);
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    SnapshotHolder snapshots;

    WebTestClient client;
    ReactiveStringRedisTemplate redis;
    Instant now;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        redis = RedisContainer.fresh();
        now = Instant.now();
        // 노드가 같은 판정 재료를 시험 사이에 들고 있다 — 용량을 모르는 재료로 되돌린다
        TestSnapshots.put(snapshots, Map.of(), new SnapshotMeta(150, 1, MaxWait.unlimited()));
    }

    /** 운영값 300, preorder 3대(태스크당 100, 하나는 예열 중)로 용량 합 250, 최대 5대면 500. */
    private void capacityKnown() {
        capacity(new CapacityStatus(300, 250, 2, 1, 500, false));
    }

    private void capacity(CapacityStatus capacity) {
        TestSnapshots.put(snapshots, new SnapshotMeta(200, 1, MaxWait.unlimited()), 0.8, capacity,
                Optional.of(new RelayOutcome(200, 30)));
    }

    private WebTestClient.RequestHeadersSpec<?> put(String path, Map<String, Object> body) {
        return client.put().uri(BASE + path).headers(headers -> headers.setBearerAuth(TestJwts.admin(now))).bodyValue(body);
    }

    private WebTestClient.ResponseSpec get(String path) {
        return client.get().uri(BASE + path).headers(headers -> headers.setBearerAuth(TestJwts.admin(now))).exchange();
    }

    private WebTestClient.ResponseSpec delete(String path) {
        return client.delete().uri(BASE + path).headers(headers -> headers.setBearerAuth(TestJwts.admin(now))).exchange();
    }

    private String setting(String field) {
        return (String) redis.opsForHash().get(RedisKeys.SETTINGS, field).block(WAIT);
    }

    @Test
    void 관리자만_쓸_수_있다() {
        client.get().uri(BASE + "/admission-rate").exchange().expectStatus().isUnauthorized();
        client.get().uri(BASE + "/admission-rate").headers(headers -> headers.setBearerAuth(TestJwts.user(customerId(1), now)))
                .exchange().expectStatus().isForbidden();
        client.put().uri(BASE + "/admission-rate").headers(headers -> headers.setBearerAuth(TestJwts.user(customerId(1), now)))
                .bodyValue(Map.of("globalCredit", 0)).exchange().expectStatus().isForbidden();
        client.delete().uri(BASE + "/max-wait").headers(headers -> headers.setBearerAuth(TestJwts.user(customerId(1), now)))
                .exchange().expectStatus().isForbidden();
        client.put().uri(BASE + "/leave-grace").headers(headers -> headers.setBearerAuth(TestJwts.user(customerId(1), now)))
                .bodyValue(Map.of("seconds", 600)).exchange().expectStatus().isForbidden();
        client.get().uri(BASE + "/changes").headers(headers -> headers.setBearerAuth(TestJwts.user(customerId(1), now)))
                .exchange().expectStatus().isForbidden();

        assertThat(redis.opsForHash().size(RedisKeys.SETTINGS).block(WAIT)).isZero();
    }

    @Test
    void 소수나_문자열은_잘라_받지_않고_400_이다() {
        put("/admission-rate", Map.of("globalCredit", 0.5)).exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.details.field").isEqualTo("globalCredit");
        put("/max-wait", Map.of("seconds", "60")).exchange().expectStatus().isBadRequest();
        put("/products/" + productKey(101) + "/admission-rate", Map.of("cap", 1.9)).exchange().expectStatus().isBadRequest();
        put("/max-wait", Map.of("seconds", 60.0)).exchange().expectStatus().isBadRequest();
        put("/max-wait", Map.of("seconds", new BigInteger("9223372036854775808"))).exchange().expectStatus().isBadRequest();
        client.put().uri(BASE + "/admission-rate").headers(headers -> headers.setBearerAuth(TestJwts.admin(now)))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"globalCredit\": null}")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error.details.field").isEqualTo("globalCredit");

        assertThat(redis.opsForHash().size(RedisKeys.SETTINGS).block(WAIT)).as("0.5 가 입장 정지로 들어가지 않는다").isZero();
    }

    @Test
    void 저장된_값이_깨져_리더가_기본값을_쓰면_출처도_기본값이다() {
        redis.opsForHash().put(RedisKeys.SETTINGS, "global-credit", "-5").block(WAIT);
        redis.opsForHash().put(RedisKeys.SETTINGS, "max-wait-sec", "0").block(WAIT);
        redis.opsForHash().put(RedisKeys.SETTINGS, "leave-grace-sec", "10").block(WAIT);

        get("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(150)
                .jsonPath("$.data.globalCredit.source").isEqualTo("DEFAULT")
                .jsonPath("$.data.maxWaitSeconds.value").isEmpty()
                .jsonPath("$.data.maxWaitSeconds.source").isEqualTo("DEFAULT")
                .jsonPath("$.data.leaveGraceSeconds.value").isEqualTo(250)
                .jsonPath("$.data.leaveGraceSeconds.source").isEqualTo("DEFAULT");
    }

    @Test
    void 운영값이_없으면_코드_기본값과_출처를_보여_준다() {
        get("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(150)
                .jsonPath("$.data.globalCredit.source").isEqualTo("DEFAULT")
                .jsonPath("$.data.maxWaitSeconds.value").isEmpty()
                .jsonPath("$.data.maxWaitSeconds.source").isEqualTo("DEFAULT")
                .jsonPath("$.data.leaveGraceSeconds.value").isEqualTo(250)
                .jsonPath("$.data.leaveGraceSeconds.source").isEqualTo("DEFAULT");
    }

    @Test
    void 입장_속도와_모델별_상한은_배포_설정의_최댓값까지_받고_Redis_에_남은_더_큰_값은_최댓값으로_자른다() {
        put("/admission-rate", Map.of("globalCredit", 1_500)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(1_500)
                .jsonPath("$.data.maxGlobalCredit").isEqualTo(1_500);
        put("/products/" + productKey(101) + "/admission-rate", Map.of("cap", 1_500)).exchange().expectStatus().isOk();

        redis.opsForHash().put(RedisKeys.SETTINGS, "global-credit", "15000").block(WAIT);
        redis.opsForHash().put(RedisKeys.SETTINGS, "cap:" + productKey(202), "15000").block(WAIT);
        get("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(1_500)
                .jsonPath("$.data.products[1].productId").isEqualTo(productKey(202))
                .jsonPath("$.data.products[1].cap.value").isEqualTo(1_500);
    }

    @Test
    void 이탈_유예는_250초에서_1800초_사이로_바꾸고_지우면_기본값으로_돌아간다() {
        put("/leave-grace", Map.of("seconds", 600)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.leaveGraceSeconds.value").isEqualTo(600)
                .jsonPath("$.data.leaveGraceSeconds.source").isEqualTo("OPERATIONAL");
        assertThat(setting("leave-grace-sec")).isEqualTo("600");

        put("/leave-grace", Map.of("seconds", 249)).exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.details.field").isEqualTo("seconds");
        put("/leave-grace", Map.of("seconds", 1_801)).exchange().expectStatus().isBadRequest();
        put("/leave-grace", Map.of()).exchange().expectStatus().isBadRequest();
        assertThat(setting("leave-grace-sec")).as("거절한 값은 쓰지 않는다").isEqualTo("600");

        delete("/leave-grace").expectStatus().isOk().expectBody()
                .jsonPath("$.data.leaveGraceSeconds.value").isEqualTo(250)
                .jsonPath("$.data.leaveGraceSeconds.source").isEqualTo("DEFAULT");
        assertThat(setting("leave-grace-sec")).isNull();
    }

    @Test
    void 전역_속도를_바꾸고_0_은_입장_일시_정지로_받고_지우면_기본값으로_돌아간다() {
        put("/admission-rate", Map.of("globalCredit", 0)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(0)
                .jsonPath("$.data.globalCredit.source").isEqualTo("OPERATIONAL");
        assertThat(setting("global-credit")).isEqualTo("0");

        delete("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(150)
                .jsonPath("$.data.globalCredit.source").isEqualTo("DEFAULT");
        assertThat(setting("global-credit")).isNull();
    }

    @Test
    void 범위를_벗어나거나_빠진_값은_400_이고_바꾸지_않는다() {
        put("/admission-rate", Map.of("globalCredit", -1)).exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.error.details.field").isEqualTo("globalCredit");
        put("/admission-rate", Map.of("globalCredit", 1_501)).exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.message").value(message -> assertThat((String) message).contains("1500"));
        put("/products/" + productKey(101) + "/admission-rate", Map.of("cap", 1_501)).exchange().expectStatus().isBadRequest();
        put("/admission-rate", Map.of()).exchange().expectStatus().isBadRequest();
        put("/products/" + productKey(101) + "/admission-rate", Map.of("cap", 0)).exchange().expectStatus().isBadRequest();
        put("/products/101/admission-rate", Map.of("cap", 5)).exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.details.field").isEqualTo("productId");
        put("/max-wait", Map.of("seconds", 0)).exchange().expectStatus().isBadRequest();

        assertThat(redis.opsForHash().size(RedisKeys.SETTINGS).block(WAIT)).isZero();
    }

    @Test
    void 모델별_상한과_최대_대기_시간을_정하면_줄_상한을_입장_속도로_계산해_보여_준다() {
        SalesWindow window = new SalesWindow(now.minusSeconds(60), now.plusSeconds(3_600));
        TestSnapshots.put(snapshots, Map.of(productKey(101), ProductState.withQueue(5, 10, window, ProductState.UNLIMITED_CAP),
                productKey(202), ProductState.idle(window, ProductState.UNLIMITED_CAP)), new SnapshotMeta(100, 1, MaxWait.unlimited()));

        put("/products/" + productKey(101) + "/admission-rate", Map.of("cap", 7)).exchange().expectStatus().isOk();
        put("/products/" + productKey(303) + "/admission-rate", Map.of("cap", 3)).exchange().expectStatus().isOk();
        put("/max-wait", Map.of("seconds", 60)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.maxWaitSeconds.value").isEqualTo(60)
                .jsonPath("$.data.products[0].productId").isEqualTo(productKey(101))
                .jsonPath("$.data.products[0].cap.value").isEqualTo(7)
                .jsonPath("$.data.products[0].cap.source").isEqualTo("OPERATIONAL")
                .jsonPath("$.data.products[0].queueLimit").isEqualTo(300)
                .jsonPath("$.data.products[1].productId").isEqualTo(productKey(202))
                .jsonPath("$.data.products[1].cap.value").isEqualTo(150)
                .jsonPath("$.data.products[1].cap.source").isEqualTo("DEFAULT")
                .jsonPath("$.data.products[1].queueLimit").isEqualTo(60)
                .jsonPath("$.data.products[2].productId").isEqualTo(productKey(303))
                // 판정 재료에 아직 없는 모델은 가장 낮은 속도로 잰다
                .jsonPath("$.data.products[2].queueLimit").isEqualTo(60);
        assertThat(setting("cap:" + productKey(101))).isEqualTo("7");

        delete("/products/" + productKey(101) + "/admission-rate").expectStatus().isOk();
        delete("/products/" + productKey(303) + "/admission-rate").expectStatus().isOk();
        delete("/max-wait").expectStatus().isOk().expectBody()
                .jsonPath("$.data.maxWaitSeconds.value").isEmpty()
                .jsonPath("$.data.products[0].queueLimit").isEmpty();
        assertThat(redis.opsForHash().size(RedisKeys.SETTINGS).block(WAIT)).isZero();
    }

    @Test
    void 입장_속도_기준으로_권장_상한_위험선_적용값_감속_상태를_보여_준다() {
        capacityKnown();
        redis.opsForHash().put(RedisKeys.SETTINGS, "global-credit", "300").block(WAIT);

        get("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.capacity.recommendedMax").isEqualTo(250)
                .jsonPath("$.data.capacity.dangerLine").isEqualTo(500)
                .jsonPath("$.data.capacity.appliedCredit").isEqualTo(200)
                .jsonPath("$.data.capacity.readyTasks").isEqualTo(2)
                .jsonPath("$.data.capacity.warmingTasks").isEqualTo(1)
                .jsonPath("$.data.capacity.brakeFactor").isEqualTo(0.8)
                .jsonPath("$.data.capacity.relayBadRatio").isEqualTo(0.15)
                .jsonPath("$.data.capacity.scalingPending").isEqualTo(true);
    }

    @Test
    void 용량을_모르면_기준은_비우고_최댓값까지_받는다() {
        get("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.capacity.recommendedMax").isEmpty()
                .jsonPath("$.data.capacity.dangerLine").isEmpty()
                .jsonPath("$.data.capacity.readyTasks").isEmpty()
                .jsonPath("$.data.capacity.relayBadRatio").isEmpty()
                .jsonPath("$.data.capacity.scalingPending").isEqualTo(false);
        put("/admission-rate", Map.of("globalCredit", 1_500)).exchange().expectStatus().isOk();
    }

    @Test
    void 리더가_직전_용량을_유지하는_중이면_지금_잰_값이_아니라_구간을_나누지_않는다() {
        capacity(new CapacityStatus(300, 250, 2, 1, 500, true));

        get("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.capacity.recommendedMax").isEmpty()
                .jsonPath("$.data.capacity.dangerLine").isEmpty()
                .jsonPath("$.data.capacity.appliedCredit").isEqualTo(200);
        put("/admission-rate", Map.of("globalCredit", 1_500)).exchange().expectStatus().isOk();
    }

    @Test
    void 용량_합을_넘겨_늘리면_확인이_있어야_저장하고_위험선을_넘으면_거절한다() {
        capacityKnown();
        redis.opsForHash().put(RedisKeys.SETTINGS, "global-credit", "100").block(WAIT);

        put("/admission-rate", Map.of("globalCredit", 250)).exchange().expectStatus().isOk();
        put("/admission-rate", Map.of("globalCredit", 400)).exchange().expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.error.code").isEqualTo("ADMISSION_RATE_NEEDS_CONFIRM")
                .jsonPath("$.error.details.recommendedMax").isEqualTo(250)
                .jsonPath("$.error.details.dangerLine").isEqualTo(500);
        assertThat(setting("global-credit")).as("확인 전에는 쓰지 않는다").isEqualTo("250");
        put("/admission-rate", Map.of("globalCredit", 501, "confirm", true)).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("ADMISSION_RATE_UNREACHABLE");
        assertThat(setting("global-credit")).isEqualTo("250");

        put("/admission-rate", Map.of("globalCredit", 400, "confirm", true)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(400)
                .jsonPath("$.data.capacity.scalingPending").isEqualTo(true);
        // 줄이는 것은 용량 합보다 커도 확인 없이 받는다
        put("/admission-rate", Map.of("globalCredit", 300)).exchange().expectStatus().isOk();
        assertThat(setting("global-credit")).isEqualTo("300");
    }

    @Test
    void 현황은_받은_판정_재료의_모델별_상태와_리더를_보여_준다() {
        SalesWindow window = new SalesWindow(now.minusSeconds(60), now.plusSeconds(3_600));
        TestSnapshots.put(snapshots, Map.of(productKey(101), ProductState.withQueue(5, 10, window, 7)),
                new SnapshotMeta(80, 2, MaxWait.of(Duration.ofSeconds(120)), Duration.ofSeconds(600)), 0.5);
        redis.opsForValue().set(RedisKeys.LEADER, "9|node-a").block(WAIT);

        get("/status").expectStatus().isOk().expectBody()
                .jsonPath("$.data.leaderNodeId").isEqualTo("node-a")
                .jsonPath("$.data.nodeId").isNotEmpty()
                .jsonPath("$.data.gateways").isEqualTo(2)
                .jsonPath("$.data.globalCredit").isEqualTo(80)
                .jsonPath("$.data.brakeFactor").isEqualTo(0.5)
                .jsonPath("$.data.maxWaitSeconds").isEqualTo(120)
                .jsonPath("$.data.leaveGraceSeconds").isEqualTo(600)
                .jsonPath("$.data.products[0].productId").isEqualTo(productKey(101))
                .jsonPath("$.data.products[0].phase").isEqualTo("OPEN")
                .jsonPath("$.data.products[0].waiting").isEqualTo(10)
                .jsonPath("$.data.products[0].credit").isEqualTo(5)
                .jsonPath("$.data.products[0].cap").isEqualTo(7)
                .jsonPath("$.data.products[0].estimatedWaitSeconds").isEqualTo(2)
                .jsonPath("$.data.longWaitThresholdSeconds").isEqualTo(120);
    }

    @Test
    void 현황의_맨_뒤_예상_대기는_사용자_화면과_같은_식으로_재고_모르면_비운다() {
        SalesWindow open = new SalesWindow(now.minusSeconds(60), now.plusSeconds(3_600));
        SalesWindow closed = new SalesWindow(now.minusSeconds(3_600), now.minusSeconds(60));
        TestSnapshots.put(snapshots, Map.of(
                productKey(101), ProductState.withQueue(3, 10, open, 150),
                productKey(102), ProductState.withQueue(0, 10, open, 4),
                productKey(103), ProductState.idle(open, 150),
                productKey(104), ProductState.hidden(10, open, 150),
                productKey(105), ProductState.withQueue(3, 10, closed, 150),
                productKey(106), ProductState.closed(10, open, 150)), new SnapshotMeta(80, 1, MaxWait.unlimited()));

        get("/status").expectStatus().isOk().expectBody()
                .jsonPath("$.data.longWaitThresholdSeconds").isEmpty()
                .jsonPath("$.data.products[0].estimatedWaitSeconds").isEqualTo(4)
                // 몫을 아직 못 받았으면 전역 속도와 상품 상한 중 작은 쪽(4)으로 잰다
                .jsonPath("$.data.products[1].estimatedWaitSeconds").isEqualTo(3)
                .jsonPath("$.data.products[2].estimatedWaitSeconds").isEqualTo(0)
                .jsonPath("$.data.products[3].estimatedWaitSeconds").isEmpty()
                .jsonPath("$.data.products[4].estimatedWaitSeconds").isEmpty()
                // 이 노드 시계로는 접수 중이어도 리더가 마감한 줄
                .jsonPath("$.data.products[5].estimatedWaitSeconds").isEmpty();

        TestSnapshots.put(snapshots, Map.of(productKey(101), ProductState.withQueue(0, 10, open, 150),
                productKey(102), ProductState.idle(open, 150)), new SnapshotMeta(0, 1, MaxWait.unlimited()));
        // 전체 입장 정지 — 줄이 비어도 바로 입장이 아니다
        get("/status").expectStatus().isOk().expectBody()
                .jsonPath("$.data.products[0].estimatedWaitSeconds").isEmpty()
                .jsonPath("$.data.products[1].estimatedWaitSeconds").isEmpty();
    }

    @Test
    void 운영값_변경을_최신부터_남기고_커서로_이어_읽는다() {
        put("/admission-rate", Map.of("globalCredit", 100)).exchange().expectStatus().isOk();
        put("/products/" + productKey(101) + "/admission-rate", Map.of("cap", 7)).exchange().expectStatus().isOk();
        put("/max-wait", Map.of("seconds", 60)).exchange().expectStatus().isOk();
        put("/leave-grace", Map.of("seconds", 600)).exchange().expectStatus().isOk();
        delete("/max-wait").expectStatus().isOk();
        put("/max-wait", Map.of("seconds", 0)).exchange().expectStatus().isBadRequest();
        put("/leave-grace", Map.of("seconds", 600)).exchange().expectStatus().isOk();
        delete("/max-wait").expectStatus().isOk();

        String cursor = nextCursor(get("/changes?size=2").expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(2)
                .jsonPath("$.data.items[0].kind").isEqualTo("MAX_WAIT")
                .jsonPath("$.data.items[0].from").isEqualTo(60)
                .jsonPath("$.data.items[0].to").isEmpty()
                .jsonPath("$.data.items[0].by").isEqualTo("admin")
                .jsonPath("$.data.items[0].at").isNotEmpty()
                .jsonPath("$.data.items[1].kind").isEqualTo("LEAVE_GRACE")
                .jsonPath("$.data.items[1].from").isEmpty()
                .jsonPath("$.data.items[1].to").isEqualTo(600));
        cursor = nextCursor(get("/changes?size=2&cursor=" + cursor).expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[0].kind").isEqualTo("MAX_WAIT")
                .jsonPath("$.data.items[0].to").isEqualTo(60)
                .jsonPath("$.data.items[1].kind").isEqualTo("PRODUCT_CREDIT")
                .jsonPath("$.data.items[1].productId").isEqualTo(productKey(101))
                .jsonPath("$.data.items[1].to").isEqualTo(7));
        get("/changes?size=2&cursor=" + cursor).expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].kind").isEqualTo("GLOBAL_CREDIT")
                .jsonPath("$.data.items[0].productId").isEmpty()
                .jsonPath("$.data.items[0].to").isEqualTo(100)
                .jsonPath("$.data.items[0].confirmed").isEqualTo(false)
                .jsonPath("$.data.nextCursor").isEmpty();

        get("/changes?to=" + now.minusSeconds(3_600)).expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(0)
                .jsonPath("$.data.nextCursor").isEmpty();
        get("/changes?from=" + now.minusSeconds(3_600) + "&to=" + now.plusSeconds(3_600) + "&cursor=")
                .expectStatus().isOk().expectBody().jsonPath("$.data.items.length()").isEqualTo(5);
    }

    @Test
    void 모르는_운영값의_이력은_건너뛰고_그_뒤의_변경까지_채워_준다() {
        put("/max-wait", Map.of("seconds", 60)).exchange().expectStatus().isOk();
        for (int i = 0; i < 3; i++) {
            redis.opsForStream().add(RedisKeys.SETTING_CHANGES,
                    Map.of("by", "admin", "field", "future-setting", "confirmed", "0", "to", String.valueOf(i))).block(WAIT);
        }
        put("/leave-grace", Map.of("seconds", 600)).exchange().expectStatus().isOk();

        String cursor = nextCursor(get("/changes?size=1").expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[0].kind").isEqualTo("LEAVE_GRACE"));
        get("/changes?size=1&cursor=" + cursor).expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].kind").isEqualTo("MAX_WAIT")
                .jsonPath("$.data.nextCursor").isEmpty();
        get("/changes?size=2").expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(2)
                .jsonPath("$.data.nextCursor").isEmpty();
    }

    @Test
    void 권장_상한을_넘겨_확인을_받고_늘린_변경은_이력에_확인으로_남는다() {
        capacityKnown();
        put("/admission-rate", Map.of("globalCredit", 250)).exchange().expectStatus().isOk();
        put("/admission-rate", Map.of("globalCredit", 400, "confirm", true)).exchange().expectStatus().isOk();
        put("/admission-rate", Map.of("globalCredit", 300, "confirm", true)).exchange().expectStatus().isOk();

        get("/changes").expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[0].from").isEqualTo(400)
                .jsonPath("$.data.items[0].to").isEqualTo(300)
                .jsonPath("$.data.items[0].confirmed").isEqualTo(false)
                .jsonPath("$.data.items[1].to").isEqualTo(400)
                .jsonPath("$.data.items[1].confirmed").isEqualTo(true)
                .jsonPath("$.data.items[2].to").isEqualTo(250)
                .jsonPath("$.data.items[2].confirmed").isEqualTo(false);
    }

    @Test
    void 이력_조회의_기간_커서_크기가_올바르지_않으면_400_이다() {
        get("/changes?size=0").expectStatus().isBadRequest().expectBody().jsonPath("$.error.details.field").isEqualTo("size");
        get("/changes?size=101").expectStatus().isBadRequest().expectBody().jsonPath("$.error.details.field").isEqualTo("size");
        get("/changes?size=1.5").expectStatus().isBadRequest().expectBody().jsonPath("$.error.details.field").isEqualTo("size");
        get("/changes?cursor=abc").expectStatus().isBadRequest().expectBody().jsonPath("$.error.details.field").isEqualTo("cursor");
        get("/changes?from=yesterday").expectStatus().isBadRequest().expectBody().jsonPath("$.error.details.field").isEqualTo("from");
        get("/changes?from=1969-12-31T23:59:59Z").expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.details.field").isEqualTo("from");
        // 값을 URI 변수로 넘겨 + 가 공백으로 풀리지 않게 한다 — 형식 오류가 아니라 상한 검사로 거절되는지 본다
        client.get().uri(BASE + "/changes?to={to}", "+1000000000-01-01T00:00:00Z")
                .headers(headers -> headers.setBearerAuth(TestJwts.admin(now))).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.error.details.field").isEqualTo("to");
        get("/changes?from=" + now + "&to=" + now.minusSeconds(1)).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.details.field").isEqualTo("from");
    }

    private static String nextCursor(WebTestClient.BodyContentSpec body) {
        AtomicReference<String> cursor = new AtomicReference<>();
        body.jsonPath("$.data.nextCursor").value(value -> cursor.set((String) value));
        assertThat(cursor.get()).isNotBlank();
        return cursor.get();
    }
}
