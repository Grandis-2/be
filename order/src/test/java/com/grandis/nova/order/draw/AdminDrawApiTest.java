package com.grandis.nova.order.draw;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 럭키 드로우 회차(만들기 · 목록 · 상세). catalog 는 대역, 재고는 표에 직접 심는다.
 * 대역 구성은 CartApiTest 와 같다 — Spring 컨텍스트를 함께 쓴다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class AdminDrawApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AdminDrawService service;
    @Autowired StockLedger stockLedger;
    @Autowired DrawCampaignStore campaignStore;
    @MockitoBean CatalogClient catalogClient;
    /** 쓰지 않지만 CartApiTest 와 같은 대역 구성으로 두어 Spring 컨텍스트를 함께 쓴다. */
    @MockitoSpyBean CartStore store;

    OrderFixtures fixtures;
    Map<UUID, CatalogOption> catalog = new HashMap<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        given(catalogClient.getOptions(any(), any())).willAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ApiResponse.ok(new CatalogOptions(ids.stream().filter(catalog::containsKey).map(catalog::get).toList()));
        });
    }

    @Test
    @DisplayName("비공개 증정품으로도 만든다 — 201 · Location, 당첨 인원만큼 재고 확보, 스냅샷 · 단계(응모 중)")
    void createsDrawReservingStockForWinners() throws Exception {
        UUID option = gift(10, false);
        Instant opens = Instant.now().minusSeconds(60);
        Instant closes = Instant.now().plus(Duration.ofDays(3));

        ResultActions created = create(UUID.randomUUID().toString(), body(option, 3, opens, closes), "admin-session").andExpect(status().isCreated());
        JsonNode draw = data(created);
        created.andExpect(header().string("Location", "/api/v1/admin/draws/" + draw.get("drawId").asString()));
        verify(catalogClient).getOptions(any(), eq("Bearer admin-session"));

        assertThat(draw.get("title").asString()).isEqualTo("한정판 드로우");
        assertThat(draw.get("gift").get("variantId").asString()).isEqualTo(option.toString());
        assertThat(draw.get("gift").get("productTitle").asString()).isEqualTo("콜라보 굿즈");
        assertThat(draw.get("entryFee").decimalValue()).isEqualByComparingTo("100");
        assertThat(draw.get("winnerCount").asInt()).isEqualTo(3);
        assertThat(draw.get("phase").asString()).isEqualTo("OPEN");
        assertThat(reserved(option)).isEqualTo(3);

        JsonNode detail = data(mockMvc.perform(get("/api/v1/admin/draws/{id}", draw.get("drawId").asString()).with(TestAuth.admin()))
                .andExpect(status().isOk()));
        assertThat(detail.get("gift").get("optionTitle").asString()).isEqualTo("블랙");
    }

    @Test
    @DisplayName("당첨 인원만큼 재고가 없으면 409 INSUFFICIENT_STOCK — 회차 · 확보 없음")
    void insufficientStockCreatesNothing() throws Exception {
        UUID option = gift(2, true);

        create(body(option, 3, Instant.now(), Instant.now().plus(Duration.ofDays(1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INSUFFICIENT_STOCK"));

        assertThat(reserved(option)).isZero();
        assertThat(drawsOf(option)).isZero();
    }

    @Test
    @DisplayName("증정품 조건 — 없는 옵션 · 상품과 짝이 다르면 404, 사전예약 · 판매 중지 · 재고 미등록은 400(optionId)")
    void giftMustBeAnActiveStockedOption() throws Exception {
        Instant closes = Instant.now().plus(Duration.ofDays(1));
        create(body(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(), closes)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        UUID option = gift(10, true);
        create(body(UUID.randomUUID(), option, 1, Instant.now(), closes)).andExpect(status().isNotFound());

        UUID preorder = gift(10, true);
        catalog.put(preorder, with(catalog.get(preorder), "PREORDER", "ACTIVE", "ACTIVE", true));
        expectViolation(create(body(preorder, 1, Instant.now(), closes)), "optionId");
        UUID paused = gift(10, true);
        catalog.put(paused, with(catalog.get(paused), "IN_STOCK", "ACTIVE", "PAUSED", true));
        expectViolation(create(body(paused, 1, Instant.now(), closes)), "optionId");
        UUID pausedProduct = gift(10, true);
        catalog.put(pausedProduct, with(catalog.get(pausedProduct), "IN_STOCK", "PAUSED", "ACTIVE", true));
        expectViolation(create(body(pausedProduct, 1, Instant.now(), closes)), "optionId");
        UUID unregistered = gift(10, true);
        catalog.put(unregistered, with(catalog.get(unregistered), "IN_STOCK", "ACTIVE", "ACTIVE", false));
        expectViolation(create(body(unregistered, 1, Instant.now(), closes)), "optionId");
        assertThat(reserved(preorder) + reserved(paused) + reserved(pausedProduct) + reserved(unregistered)).isZero();
    }

    @Test
    @DisplayName("입력 검증 — 마감이 지났거나 시작보다 앞 · 시작과 같음 · 저장 못 하는 시각 · 응모비 100원 미만 · 당첨 0명 · 제목 없음은 400")
    void invalidInputIsRejected() throws Exception {
        UUID option = gift(10, true);
        Instant now = Instant.now();
        expectViolation(create(body(option, 1, now.minusSeconds(120), now.minusSeconds(60))), "closesAt");
        expectViolation(create(body(option, 1, now.plusSeconds(120), now.plusSeconds(60))), "closesAt");
        Instant same = now.plusSeconds(120);
        expectViolation(create(body(option, 1, same, same)), "closesAt");
        expectViolation(create(body(option, 1, now, Instant.parse("+10000-01-01T00:00:00Z"))), "closesAt");
        expectViolation(create(body(option, 1, Instant.parse("2030-01-01T00:00:00.000000100Z"), Instant.parse("2030-01-01T00:00:00.000000900Z"))),
                "closesAt");
        expectViolation(create(raw(option, "\"\"", "100", 1, now, now.plusSeconds(600))), "title");
        expectViolation(create(raw(option, "\"t\"", "99", 1, now, now.plusSeconds(600))), "entryFee");
        expectViolation(create(raw(option, "\"t\"", "100.5", 1, now, now.plusSeconds(600))), "entryFee");
        expectViolation(create(raw(option, "\"t\"", "100", 0, now, now.plusSeconds(600))), "winnerCount");
        assertThat(reserved(option)).isZero();
    }

    @Test
    @DisplayName("시각은 µs 로 잘라 저장 · 응답한다 — 응답과 저장값이 같다")
    void timesAreTruncatedToMicros() throws Exception {
        UUID option = gift(10, true);
        Instant opens = Instant.parse("2030-01-01T00:00:00.123456789Z");
        Instant closes = Instant.parse("2030-01-02T00:00:00.999999999Z");

        JsonNode draw = data(create(body(option, 1, opens, closes)).andExpect(status().isCreated()));

        assertThat(Instant.parse(draw.get("opensAt").asString())).isEqualTo("2030-01-01T00:00:00.123456Z");
        assertThat(Instant.parse(draw.get("closesAt").asString())).isEqualTo("2030-01-02T00:00:00.999999Z");
        assertThat(storedClosesAt(draw)).isEqualTo("2030-01-02 00:00:00.999999");

        JsonNode last = data(create(body(option, 1, opens, Instant.parse("9999-12-31T23:59:59.999999999Z"))).andExpect(status().isCreated()));
        assertThat(storedClosesAt(last)).as("DB 가 담는 가장 늦은 시각까지 받는다").isEqualTo("9999-12-31 23:59:59.999999");
    }

    @Test
    @DisplayName("같은 Idempotency-Key 로 다시 오면 처음 회차를 200 으로 — 본문 대조 · catalog 조회 · 마감 검증 없이, 회차 · 확보는 하나")
    void sameKeyReturnsFirstDraw() throws Exception {
        UUID option = gift(10, true);
        String key = UUID.randomUUID().toString();
        Instant closes = Instant.now().plus(Duration.ofDays(1));
        String first = data(create(key, body(option, 3, Instant.now(), closes)).andExpect(status().isCreated())).get("drawId").asString();

        catalog.put(option, with(catalog.get(option), "IN_STOCK", "PAUSED", "ACTIVE", true));
        Instant past = Instant.now().minusSeconds(60);
        JsonNode again = data(create(" " + key + " ", body(option, 5, past.minusSeconds(60), past))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Location")));
        verify(catalogClient, times(1)).getOptions(any(), any());

        assertThat(again.get("drawId").asString()).isEqualTo(first);
        assertThat(again.get("winnerCount").asInt()).isEqualTo(3);
        assertThat(reserved(option)).isEqualTo(3);
        assertThat(drawsOf(option)).isEqualTo(1);
        UUID other = gift(10, true);
        assertThat(data(create(key.toUpperCase(), body(other, 1, Instant.now(), closes)).andExpect(status().isCreated()))
                .get("drawId").asString()).as("키는 대소문자를 가른다").isNotEqualTo(first);
    }

    @Test
    @DisplayName("Idempotency-Key 가 없으면 IDEMPOTENCY_KEY_REQUIRED, 비었거나 100자를 넘으면 400(Idempotency-Key) — 회차 · 확보 없음")
    void idempotencyKeyIsRequired() throws Exception {
        UUID option = gift(10, true);
        String body = body(option, 1, Instant.now(), Instant.now().plus(Duration.ofDays(1)));
        mockMvc.perform(post("/api/v1/admin/draws").with(TestAuth.admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        expectViolation(create("  ", body), "Idempotency-Key");
        expectViolation(create("k".repeat(101), body), "Idempotency-Key");
        create("k".repeat(100), body).andExpect(status().isCreated());
        assertThat(reserved(option)).isEqualTo(1);
    }

    /**
     * 같은 새 키가 겹치면 늦은 쪽은 키 충돌로 확보까지 롤백하고 먼저 들어간 회차를 200 으로 돌려준다. 결정적으로 본다: 앞선 쪽이 그 키의 회차를
     * 넣은 채 커밋하지 않고, 늦은 쪽이 확보한 뒤 그 키의 유일 키에서 기다리는 것을 확인한 뒤 커밋한다.
     */
    @Test
    @DisplayName("같은 새 키가 겹치면 늦은 쪽은 확보를 되돌리고 먼저 들어간 회차를 200")
    void concurrentSameKeyKeepsOneDraw() throws Exception {
        UUID option = gift(10, true);
        UUID product = catalog.get(option).productId();
        String key = UUID.randomUUID().toString();
        UUID earlier = UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO draw_campaigns (id, idempotency_key, product_id, option_id, title, product_title_snapshot, option_title_snapshot,
                                                entry_fee, winner_count, opens_at, closes_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, '먼저', 'p', 'o', 100, 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 1 DAY,
                            UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", bytes(earlier), key, bytes(product), bytes(option));
            inserted.countDown();
            await(commit);
        }));
        await(inserted);
        CompletableFuture<String> later = CompletableFuture.supplyAsync(() -> {
            try {
                return create(key, body(option, 3, Instant.now(), Instant.now().plus(Duration.ofDays(1)))).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitForQuery("insert into draw_campaigns%");
        commit.countDown();
        first.get(20, TimeUnit.SECONDS);

        JsonNode draw = JSON.readTree(later.get(20, TimeUnit.SECONDS)).get("data");
        assertThat(draw.get("drawId").asString()).isEqualTo(earlier.toString());
        assertThat(draw.get("title").asString()).isEqualTo("먼저");
        assertThat(reserved(option)).as("늦은 쪽의 확보는 롤백").isZero();
        assertThat(drawsOf(option)).isEqualTo(1);
    }

    /**
     * 재고가 회차 하나 몫뿐일 때 같은 새 키가 겹치면, 늦은 쪽은 유일 키보다 먼저 재고 행에서 기다리고 앞선 쪽 커밋 뒤 재고가 모자라 확보에 실패한다
     * — 그래도 그 키의 회차가 있으면 409 가 아니라 그 회차를 200 이다. 결정적으로 본다: 앞선 쪽이 운영 경로대로 확보 · 회차를 넣은 채 커밋하지 않고,
     * 늦은 쪽이 재고 행 UPDATE 에서 기다리는 것을 확인한 뒤 커밋한다.
     */
    @Test
    @DisplayName("재고가 한 회차 몫일 때 같은 새 키가 겹쳐도 늦은 쪽은 409 가 아니라 먼저 들어간 회차를 200")
    void concurrentSameKeyWithOnlyOneDrawOfStock() throws Exception {
        UUID option = gift(3, true);
        CatalogOption gift = catalog.get(option);
        String key = UUID.randomUUID().toString();
        Instant closes = Instant.now().plus(Duration.ofDays(1));
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<UUID> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            stockLedger.reserve(Map.of(option, 3));
            UUID id = campaignStore.insert(new NewDrawCampaign(key, gift.productId(), option, "먼저", gift.productTitle(), gift.optionTitle(),
                    gift.imageUrl(), new BigDecimal("100"), 3, Instant.now(), closes)).id();
            inserted.countDown();
            await(commit);
            return id;
        }));
        await(inserted);
        CompletableFuture<MockHttpServletResponse> later = CompletableFuture.supplyAsync(() -> {
            try {
                return create(key, body(option, 3, Instant.now(), closes)).andReturn().getResponse();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitForQuery("update option_inventories%");
        commit.countDown();
        UUID earlier = first.get(20, TimeUnit.SECONDS);

        MockHttpServletResponse response = later.get(20, TimeUnit.SECONDS);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString()).get("data").get("drawId").asString()).isEqualTo(earlier.toString());
        assertThat(reserved(option)).isEqualTo(3);
        assertThat(drawsOf(option)).isEqualTo(1);
    }

    @Test
    @DisplayName("만든 시각이 같으면 id 큰 쪽이 앞")
    void tiesOnCreatedAtOrderByIdDescending() throws Exception {
        String a = data(create(body(gift(10, true), 1, Instant.now(), Instant.now().plus(Duration.ofDays(1))))).get("drawId").asString();
        String b = data(create(body(gift(10, true), 1, Instant.now(), Instant.now().plus(Duration.ofDays(1))))).get("drawId").asString();
        // 다른 회차보다 늦은 시각으로 옮겨 첫 쪽 맨 앞 둘로 고정한다. 목록 순서를 보는 다른 시험이 있어 끝나면 지운다
        jdbcTemplate.update("UPDATE draw_campaigns SET created_at = '2100-01-01 00:00:00' WHERE id IN (?, ?)",
                bytes(UUID.fromString(a)), bytes(UUID.fromString(b)));
        try {
            JsonNode items = data(mockMvc.perform(get("/api/v1/admin/draws").param("size", "2").with(TestAuth.admin())).andExpect(status().isOk()))
                    .get("items");
            // binary(16) 은 부호 없는 바이트 순 — 16진 문자열의 사전 순과 같다
            boolean aFirst = HexFormat.of().formatHex(bytes(UUID.fromString(a))).compareTo(HexFormat.of().formatHex(bytes(UUID.fromString(b)))) > 0;
            assertThat(List.of(items.get(0).get("drawId").asString(), items.get(1).get("drawId").asString()))
                    .containsExactly(aFirst ? a : b, aFirst ? b : a);
        } finally {
            jdbcTemplate.update("DELETE FROM draw_campaigns WHERE id IN (?, ?)", bytes(UUID.fromString(a)), bytes(UUID.fromString(b)));
        }
    }

    @Test
    @DisplayName("트랜잭션 안에서 부르면 거절한다 — catalog 호출 동안 잠금을 쥐지 않게")
    void createRefusesAnOuterTransaction() {
        AdminDrawService.CreateDraw command = new AdminDrawService.CreateDraw(UUID.randomUUID(), UUID.randomUUID(), "t", new BigDecimal("100"), 1,
                Instant.now(), Instant.now().plusSeconds(600));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.create(null, "k", command)))
                .isInstanceOf(IllegalStateException.class);
        verify(catalogClient, never()).getOptions(any(), any());
    }

    @Test
    @DisplayName("목록은 최신순 · 전체 건수, 단계는 시각으로 — 시작 전 SCHEDULED. 없는 회차 404")
    void listsNewestFirstWithPhase() throws Exception {
        UUID first = gift(10, true);
        UUID second = gift(10, true);
        create(body(first, 1, Instant.now(), Instant.now().plus(Duration.ofDays(1)))).andExpect(status().isCreated());
        String later = data(create(body(second, 1, Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(2))))
                .andExpect(status().isCreated())).get("drawId").asString();

        JsonNode page = data(mockMvc.perform(get("/api/v1/admin/draws").param("size", "1").with(TestAuth.admin())).andExpect(status().isOk()));
        assertThat(page.get("items").get(0).get("drawId").asString()).isEqualTo(later);
        assertThat(page.get("items").get(0).get("phase").asString()).isEqualTo("SCHEDULED");
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(2);
        mockMvc.perform(get("/api/v1/admin/draws").param("size", "0").with(TestAuth.admin())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/draws/{id}", UUID.randomUUID()).with(TestAuth.admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DRAW_NOT_FOUND"));
    }

    @Test
    @DisplayName("관리자만 — 익명 401, 회원 403")
    void adminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/draws")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/draws").with(TestAuth.customer(UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    /** 일반 판매 · 판매 중 · 재고 등록 옵션. visible=false 면 매장에 안 보이는 증정품이다. */
    private UUID gift(int stock, boolean visible) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        fixtures.stock(option, stock, 0, 0);
        catalog.put(option, new CatalogOption(option, product.productId(), "콜라보 굿즈", "블랙", "SKU-" + option, new BigDecimal("50000"),
                "ACTIVE", "IN_STOCK", "ACTIVE", visible, true, new CatalogOption.Warranty(false, BigDecimal.ZERO), "https://img/gift.jpg"));
        return option;
    }

    private static CatalogOption with(CatalogOption o, String saleMode, String productStatus, String optionStatus, boolean registered) {
        return new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), o.price(), optionStatus, saleMode,
                productStatus, o.visible(), registered, o.warranty(), o.imageUrl());
    }

    private String body(UUID option, int winners, Instant opens, Instant closes) {
        return body(catalog.get(option).productId(), option, winners, opens, closes);
    }

    private static String body(UUID product, UUID option, int winners, Instant opens, Instant closes) {
        return """
                {"productId":"%s","optionId":"%s","title":"한정판 드로우","entryFee":100,"winnerCount":%d,"opensAt":"%s","closesAt":"%s"}"""
                .formatted(product, option, winners, opens, closes);
    }

    private String raw(UUID option, String title, String fee, int winners, Instant opens, Instant closes) {
        return """
                {"productId":"%s","optionId":"%s","title":%s,"entryFee":%s,"winnerCount":%d,"opensAt":"%s","closesAt":"%s"}"""
                .formatted(catalog.get(option).productId(), option, title, fee, winners, opens, closes);
    }

    private ResultActions create(String body) throws Exception {
        return create(UUID.randomUUID().toString(), body);
    }

    private ResultActions create(String key, String body) throws Exception {
        return create(key, body, null);
    }

    private ResultActions create(String key, String body, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/admin/draws").with(TestAuth.admin()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(accessToken == null ? request : request.header("Authorization", "Bearer " + accessToken));
    }

    private void waitForQuery(String like) {
        Awaitility.await("그 문장이 기다리지 않았다: " + like).atMost(Duration.ofSeconds(10)).until(() -> {
            Long running = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND = 'Query' AND INFO LIKE ?", Long.class, like);
            return running != null && running > 0;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다리다 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String storedClosesAt(JsonNode draw) {
        return jdbcTemplate.queryForObject("SELECT DATE_FORMAT(closes_at, '%Y-%m-%d %T.%f') FROM draw_campaigns WHERE id = ?", String.class,
                (Object) bytes(UUID.fromString(draw.get("drawId").asString())));
    }

    private int reserved(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_reserved FROM option_inventories WHERE option_id = ?", Integer.class,
                (Object) bytes(option));
    }

    private long drawsOf(UUID option) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM draw_campaigns WHERE option_id = ?", Long.class, (Object) bytes(option));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static void expectViolation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }
}
