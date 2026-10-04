package com.grandis.nova.waitingroom.admin;

import com.grandis.nova.waitingroom.control.SnapshotHolder;
import com.grandis.nova.waitingroom.control.TestSnapshots;
import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
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
        client.get().uri(BASE + "/admission-rate").headers(headers -> headers.setBearerAuth(TestJwts.user("1", now)))
                .exchange().expectStatus().isForbidden();
        client.put().uri(BASE + "/admission-rate").headers(headers -> headers.setBearerAuth(TestJwts.user("1", now)))
                .bodyValue(Map.of("globalCredit", 0)).exchange().expectStatus().isForbidden();
        client.delete().uri(BASE + "/max-wait").headers(headers -> headers.setBearerAuth(TestJwts.user("1", now)))
                .exchange().expectStatus().isForbidden();

        assertThat(redis.opsForHash().size(RedisKeys.SETTINGS).block(WAIT)).isZero();
    }

    @Test
    void 소수나_문자열은_잘라_받지_않고_400_이다() {
        put("/admission-rate", Map.of("globalCredit", 0.5)).exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.details.field").isEqualTo("globalCredit");
        put("/max-wait", Map.of("seconds", "60")).exchange().expectStatus().isBadRequest();
        put("/products/101/admission-rate", Map.of("cap", 1.9)).exchange().expectStatus().isBadRequest();
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

        get("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(100)
                .jsonPath("$.data.globalCredit.source").isEqualTo("DEFAULT")
                .jsonPath("$.data.maxWaitSeconds.value").isEmpty()
                .jsonPath("$.data.maxWaitSeconds.source").isEqualTo("DEFAULT");
    }

    @Test
    void 운영값이_없으면_코드_기본값과_출처를_보여_준다() {
        get("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(100)
                .jsonPath("$.data.globalCredit.source").isEqualTo("DEFAULT")
                .jsonPath("$.data.maxWaitSeconds.value").isEmpty()
                .jsonPath("$.data.maxWaitSeconds.source").isEqualTo("DEFAULT");
    }

    @Test
    void 전역_속도를_바꾸고_0_은_입장_일시_정지로_받고_지우면_기본값으로_돌아간다() {
        put("/admission-rate", Map.of("globalCredit", 0)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(0)
                .jsonPath("$.data.globalCredit.source").isEqualTo("OPERATIONAL");
        assertThat(setting("global-credit")).isEqualTo("0");

        delete("/admission-rate").expectStatus().isOk().expectBody()
                .jsonPath("$.data.globalCredit.value").isEqualTo(100)
                .jsonPath("$.data.globalCredit.source").isEqualTo("DEFAULT");
        assertThat(setting("global-credit")).isNull();
    }

    @Test
    void 범위를_벗어나거나_빠진_값은_400_이고_바꾸지_않는다() {
        put("/admission-rate", Map.of("globalCredit", -1)).exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.error.details.field").isEqualTo("globalCredit");
        put("/admission-rate", Map.of("globalCredit", OperationalSettingsAdmin.MAX_GLOBAL_CREDIT + 1)).exchange()
                .expectStatus().isBadRequest();
        put("/admission-rate", Map.of()).exchange().expectStatus().isBadRequest();
        put("/products/101/admission-rate", Map.of("cap", 0)).exchange().expectStatus().isBadRequest();
        put("/products/0101/admission-rate", Map.of("cap", 5)).exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error.details.field").isEqualTo("productId");
        put("/max-wait", Map.of("seconds", 0)).exchange().expectStatus().isBadRequest();

        assertThat(redis.opsForHash().size(RedisKeys.SETTINGS).block(WAIT)).isZero();
    }

    @Test
    void 모델별_상한과_최대_대기_시간을_정하면_줄_상한을_입장_속도로_계산해_보여_준다() {
        SalesWindow window = new SalesWindow(now.minusSeconds(60), now.plusSeconds(3_600));
        TestSnapshots.put(snapshots, Map.of("101", ProductState.withQueue(5, 10, window, ProductState.UNLIMITED_CAP),
                "202", ProductState.idle(window, ProductState.UNLIMITED_CAP)), new SnapshotMeta(100, 1, MaxWait.unlimited()));

        put("/products/101/admission-rate", Map.of("cap", 7)).exchange().expectStatus().isOk();
        put("/products/303/admission-rate", Map.of("cap", 3)).exchange().expectStatus().isOk();
        put("/max-wait", Map.of("seconds", 60)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.maxWaitSeconds.value").isEqualTo(60)
                .jsonPath("$.data.products[0].productId").isEqualTo("101")
                .jsonPath("$.data.products[0].cap.value").isEqualTo(7)
                .jsonPath("$.data.products[0].cap.source").isEqualTo("OPERATIONAL")
                .jsonPath("$.data.products[0].queueLimit").isEqualTo(300)
                .jsonPath("$.data.products[1].productId").isEqualTo("202")
                .jsonPath("$.data.products[1].cap.value").isEmpty()
                .jsonPath("$.data.products[1].queueLimit").isEqualTo(60)
                .jsonPath("$.data.products[2].productId").isEqualTo("303")
                // 판정 재료에 아직 없는 모델은 가장 낮은 속도로 잰다
                .jsonPath("$.data.products[2].queueLimit").isEqualTo(60);
        assertThat(setting("cap:101")).isEqualTo("7");

        delete("/products/101/admission-rate").expectStatus().isOk();
        delete("/products/303/admission-rate").expectStatus().isOk();
        delete("/max-wait").expectStatus().isOk().expectBody()
                .jsonPath("$.data.maxWaitSeconds.value").isEmpty()
                .jsonPath("$.data.products[0].queueLimit").isEmpty();
        assertThat(redis.opsForHash().size(RedisKeys.SETTINGS).block(WAIT)).isZero();
    }

    @Test
    void 현황은_받은_판정_재료의_모델별_상태와_리더를_보여_준다() {
        SalesWindow window = new SalesWindow(now.minusSeconds(60), now.plusSeconds(3_600));
        TestSnapshots.put(snapshots, Map.of("101", ProductState.withQueue(5, 10, window, 7)),
                new SnapshotMeta(80, 2, MaxWait.of(Duration.ofSeconds(120))), 0.5);
        redis.opsForValue().set(RedisKeys.LEADER, "9|node-a").block(WAIT);

        get("/status").expectStatus().isOk().expectBody()
                .jsonPath("$.data.leaderNodeId").isEqualTo("node-a")
                .jsonPath("$.data.nodeId").isNotEmpty()
                .jsonPath("$.data.gateways").isEqualTo(2)
                .jsonPath("$.data.globalCredit").isEqualTo(80)
                .jsonPath("$.data.brakeFactor").isEqualTo(0.5)
                .jsonPath("$.data.maxWaitSeconds").isEqualTo(120)
                .jsonPath("$.data.products[0].productId").isEqualTo("101")
                .jsonPath("$.data.products[0].phase").isEqualTo("OPEN")
                .jsonPath("$.data.products[0].waiting").isEqualTo(10)
                .jsonPath("$.data.products[0].credit").isEqualTo(5)
                .jsonPath("$.data.products[0].cap").isEqualTo(7);
    }
}
