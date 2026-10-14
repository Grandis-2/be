package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사전예약 홍보 — 관리자 만들기 · 고치기 · 목록 · 묶을 수 있는 상품, 고객 목록 · 상세. */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class PromotionApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ADMIN = "/api/v1/admin/promotions";
    private static final Duration HOUR = Duration.ofHours(1);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;
    UUID categoryId;
    LocalDate today;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
        today = LocalDate.now(KOREA);
    }

    // ── 관리자 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("만들기 — 201 · 칸 그대로 · 상품은 보낸 순서 · 오늘이 기간 안이면 ONGOING. 같은 Idempotency-Key 는 업무 규칙과 상관없이 200 으로 처음 홍보")
    void createsAndReplays() throws Exception {
        UUID a = preorder("A");
        UUID b = preorder("B");
        String key = "k-" + ShopFixtures.unique();
        String body = body("아이폰 18 사전예약", today.minusDays(1), today.plusDays(3), List.of("https://img/d1.jpg", "https://img/d2.jpg"), b, a);

        JsonNode created = data(create(key, body).andExpect(status().isCreated()));
        assertThat(created.get("name").asString()).isEqualTo("아이폰 18 사전예약");
        assertThat(created.get("status").asString()).isEqualTo("ONGOING");
        assertThat(created.get("startDate").asString()).isEqualTo(today.minusDays(1).toString());
        assertThat(created.get("thumbnailUrl").asString()).isEqualTo("https://img/t.jpg");
        assertThat(created.get("detailImageUrls").toString()).isEqualTo("[\"https://img/d1.jpg\",\"https://img/d2.jpg\"]");
        assertThat(ids(created.get("products"))).containsExactly(b.toString(), a.toString());
        assertThat(created.get("products").get(0).get("saleMode").asString()).isEqualTo("PREORDER");

        JsonNode replayed = data(create(key, body(" 다른 내용", today, today, List.of(), preorder("C"))).andExpect(status().isOk()));
        assertThat(replayed.get("promotionId").asString()).isEqualTo(created.get("promotionId").asString());
        assertThat(replayed.get("name").asString()).isEqualTo("아이폰 18 사전예약");
        // 업무 규칙은 다시 판정하지 않는다 — 다시 보낸 본문의 상품이 이미 묶였어도(새 만들기라면 409) 처음 홍보를 돌려준다. 형식 검사(빈 이름 등)는 먼저 400 이다
        create(key, body("다시", today, today, List.of(), a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promotionId").value(created.get("promotionId").asString()));
    }

    @Test
    @DisplayName("같은 Idempotency-Key 로 동시에 두 번 — 늘 201 하나 · 200 하나, 같은 홍보이고 홍보 행 하나 · 묶음은 상품 수만큼")
    void concurrentReplayReturnsTheSamePromotion() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                UUID a = preorder("A" + round);
                UUID b = preorder("B" + round);
                String key = "k-" + ShopFixtures.unique();
                String body = body("동시 재전송", today, today, List.of(), a, b);
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                List<java.util.concurrent.Future<org.springframework.mock.web.MockHttpServletResponse>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return create(key, body).andReturn().getResponse();
                    }));
                }
                List<Integer> statuses = new ArrayList<>();
                List<String> promotionIds = new ArrayList<>();
                for (var future : futures) {
                    org.springframework.mock.web.MockHttpServletResponse response = future.get();
                    statuses.add(response.getStatus());
                    promotionIds.add(JSON.readTree(response.getContentAsString()).get("data").get("promotionId").asString());
                }
                assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 200);
                assertThat(promotionIds.get(0)).isEqualTo(promotionIds.get(1));
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM promotions WHERE idempotency_key = ?", Long.class, key)).isEqualTo(1L);
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM promotion_products WHERE promotion_id = ?", Long.class,
                        UuidBinary.toBytes(UUID.fromString(promotionIds.get(0))))).isEqualTo(2L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("본문 검사 — 기간 순서 · 업로드 주소 · 같은 사진 두 번 · 상품 없음 · 같은 상품 두 번 · 없는 상품 · 사전예약 아님 · 회차 취소 · 모르는 칸은 400")
    void rejectsBadBodies() throws Exception {
        UUID ok = preorder("OK");
        UUID inStock = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "일반", "t");
        UUID canceled = preorder("취소");
        jdbcTemplate.update("UPDATE products SET status = 'PAUSED', campaign_canceled_at = UTC_TIMESTAMP(6) WHERE id = ?",
                (Object) UuidBinary.toBytes(canceled));

        expectValidation(create(body("x", today, today.minusDays(1), List.of(), ok)), "endDate");
        expectValidation(create(body("x", today, today, List.of(), ok).replace("https://img/t.jpg", "https://evil/t.jpg")), "thumbnailUrl");
        expectValidation(create(body("x", today, today, List.of("https://img/a.jpg", "https://img/a.jpg"), ok)), "detailImageUrls[1]");
        expectValidation(create(body("x", today, today, List.of())), "productIds");
        expectValidation(create(body("x", today, today, List.of(), ok, ok)), "productIds[1]");
        expectValidation(create(body("x", today, today, List.of(), UUID.randomUUID())), "productIds[0]");
        expectValidation(create(body("x", today, today, List.of(), ok, inStock)), "productIds[1]");
        expectValidation(create(body("x", today, today, List.of(), canceled)), "productIds[0]");
        expectValidation(create(body("x", today, today, List.of(), ok).replace("\"name\"", "\"benefit\": \"x\", \"name\"")), "benefit");
        expectValidation(create(body("   ", today, today, List.of(), ok)), "name");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM promotion_products WHERE product_id = ?", Integer.class,
                (Object) UuidBinary.toBytes(ok))).isZero();
    }

    @Test
    @DisplayName("한 상품은 홍보 하나 — 다른 홍보에 묶인 상품은 만들기 · 고치기 모두 409(details.productIds), 풀면 다른 홍보가 묶을 수 있다")
    void productBelongsToOnePromotion() throws Exception {
        UUID a = preorder("A");
        UUID b = preorder("B");
        UUID first = promotionId(create(body("첫 홍보", today, today, List.of(), a, b)).andExpect(status().isCreated()));

        create(body("둘째", today, today, List.of(), a))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.error.details.productIds[0]").value(a.toString()));
        UUID second = promotionId(create(body("둘째", today, today, List.of(), preorder("C"))).andExpect(status().isCreated()));
        replace(second, body("둘째", today, today, List.of(), b)).andExpect(status().isConflict());

        replace(first, body("첫 홍보", today, today, List.of(), a)).andExpect(status().isOk());   // b 를 푼다
        JsonNode moved = data(replace(second, body("둘째", today, today, List.of(), b)).andExpect(status().isOk()));
        assertThat(ids(moved.get("products"))).containsExactly(b.toString());
    }

    @Test
    @DisplayName("상품을 역순으로 묶는 두 만들기가 겹쳐도 교착하지 않는다 — 늘 하나는 201, 다른 하나는 실제로 겹친 상품만 알리는 409(다시 시도 409 아님)")
    void reverseOrderCreatesDoNotDeadlock() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                UUID a = preorder("A" + round);
                UUID b = preorder("B" + round);
                UUID c = preorder("C" + round);
                UUID d = preorder("D" + round);   // a 와 d 는 한쪽에만 — 409 는 겹친 b · c 만 알려야 한다
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                List<java.util.concurrent.Future<org.springframework.mock.web.MockHttpServletResponse>> futures = new ArrayList<>();
                for (UUID[] order : new UUID[][] {{a, b, c}, {d, c, b}}) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return create(body("역순", today, today, List.of(), order)).andReturn().getResponse();
                    }));
                }
                List<Integer> statuses = new ArrayList<>();
                for (var future : futures) {
                    org.springframework.mock.web.MockHttpServletResponse response = future.get();
                    statuses.add(response.getStatus());
                    if (response.getStatus() == 409) {
                        JsonNode details = JSON.readTree(response.getContentAsString()).get("error").get("details");
                        List<String> conflicted = new ArrayList<>();
                        details.get("productIds").forEach(id -> conflicted.add(id.asString()));
                        assertThat(conflicted).as("round %d: %s", round, details).containsExactlyInAnyOrder(b.toString(), c.toString());
                    }
                }
                assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 409);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("고치기 — 순서를 맞바꿔도 된다(한 상품 한 홍보 제약에 걸리지 않게 통째로 다시 넣는다) · 일반으로 바꾼 상품도 남겨 둘 수 있다 · 없는 홍보 404")
    void replaceReordersAndKeepsConverted() throws Exception {
        UUID a = preorder("A");
        UUID b = preorder("B");
        UUID promotionId = promotionId(create(body("홍보", today, today.plusDays(1), List.of(), a, b)).andExpect(status().isCreated()));

        JsonNode swapped = data(replace(promotionId, body("홍보 고침", today, today.plusDays(2), List.of("https://img/x.jpg"), b, a))
                .andExpect(status().isOk()));
        assertThat(ids(swapped.get("products"))).containsExactly(b.toString(), a.toString());
        assertThat(swapped.get("name").asString()).isEqualTo("홍보 고침");
        assertThat(swapped.get("endDate").asString()).isEqualTo(today.plusDays(2).toString());

        jdbcTemplate.update("UPDATE products SET sale_mode = 'IN_STOCK' WHERE id = ?", (Object) UuidBinary.toBytes(a));   // 일반 전환
        JsonNode kept = data(replace(promotionId, body("홍보 고침", today, today.plusDays(2), List.of(), a)).andExpect(status().isOk()));
        assertThat(kept.get("products").get(0).get("saleMode").asString()).isEqualTo("IN_STOCK");

        replace(UUID.randomUUID(), body("x", today, today, List.of(), b)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("목록 — 최신순, status 는 오늘(한국 날짜)로 SCHEDULED · ONGOING · ENDED")
    void listShowsStatusByDate() throws Exception {
        UUID ended = promotionId(create(body("끝남", today.minusDays(5), today.minusDays(1), List.of(), preorder("E"))));
        UUID ongoing = promotionId(create(body("진행", today, today, List.of(), preorder("O"))));
        UUID scheduled = promotionId(create(body("예정", today.plusDays(1), today.plusDays(2), List.of(), preorder("S"))));

        JsonNode items = data(mockMvc.perform(get(ADMIN).param("size", "100").with(admin()))).get("items");
        List<String> mine = new ArrayList<>();
        items.forEach(item -> {
            String id = item.get("promotionId").asString();
            if (id.equals(ended.toString()) || id.equals(ongoing.toString()) || id.equals(scheduled.toString())) {
                mine.add(item.get("name").asString() + ":" + item.get("status").asString());
            }
        });
        assertThat(mine).containsExactly("예정:SCHEDULED", "진행:ONGOING", "끝남:ENDED");
        assertThat(data(mockMvc.perform(get(ADMIN + "/{id}", ongoing).with(admin()))).get("status").asString()).isEqualTo("ONGOING");
        mockMvc.perform(get(ADMIN + "/{id}", UUID.randomUUID()).with(admin())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("묶을 수 있는 상품 — 회차가 취소되지 않은 사전예약 중 다른 홍보에 안 묶인 것, 오픈 시각 · 색상 · 용량. promotionId 를 주면 그 홍보에 묶인 상품(일반 전환 포함)도")
    void linkableProducts() throws Exception {
        UUID free = registerPreorder();   // 색상 · 용량 축
        UUID linked = preorder("묶임");
        UUID mineConverted = preorder("내 것");
        UUID inStock = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "일반", "t");
        UUID promotionId = promotionId(create(body("다른", today, today, List.of(), linked)));
        UUID own = promotionId(create(body("내 홍보", today, today, List.of(), mineConverted)));
        jdbcTemplate.update("UPDATE products SET sale_mode = 'IN_STOCK' WHERE id = ?", (Object) UuidBinary.toBytes(mineConverted));

        JsonNode all = linkable(null);
        assertThat(idsOf(all)).contains(free.toString()).doesNotContain(linked.toString(), mineConverted.toString(), inStock.toString());
        JsonNode freeItem = find(all, free);
        assertThat(freeItem.get("colors").toString()).isEqualTo("[\"블랙\",\"화이트\"]");
        assertThat(freeItem.get("storages").toString()).isEqualTo("[\"256GB\",\"512GB\"]");
        assertThat(freeItem.get("opensAt").isNull()).isFalse();

        JsonNode editing = linkable(own);
        assertThat(idsOf(editing)).contains(free.toString(), mineConverted.toString()).doesNotContain(linked.toString());
        assertThat(promotionId).isNotNull();
    }

    @Test
    @DisplayName("관리자만 — 회원 403 · 익명 401")
    void adminOnly() throws Exception {
        String body = body("x", today, today, List.of(), preorder("A"));
        mockMvc.perform(post(ADMIN).header("Idempotency-Key", "k-" + ShopFixtures.unique()).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(user("657").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
    }

    // ── 고객 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("고객 목록 — 오늘이 기간 안인 홍보만, opensAt · closesAt 은 묶인 공개 사전예약 상품 중 가장 이른 오픈 · 가장 늦은 마감")
    void publicListShowsOnlyTodaysPromotions() throws Exception {
        Instant now = Instant.now();
        UUID early = preorder("이른", now.minus(HOUR.multipliedBy(3)), now.plus(HOUR));
        UUID late = preorder("늦은", now.plus(HOUR), now.plus(HOUR.multipliedBy(5)));
        UUID hidden = preorder("숨김", now.minus(HOUR.multipliedBy(9)), now.plus(HOUR.multipliedBy(9)));
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", (Object) UuidBinary.toBytes(hidden));
        UUID shown = promotionId(create(body("오늘", today, today, List.of(), late, early, hidden)));
        UUID future = promotionId(create(body("내일", today.plusDays(1), today.plusDays(2), List.of(), preorder("F"))));
        UUID ended = promotionId(create(body("어제 끝남", today.minusDays(2), today.minusDays(1), List.of(), preorder("E"))));

        JsonNode items = data(mockMvc.perform(get("/api/v1/promotions")).andExpect(status().isOk())).get("items");
        assertThat(idsOf(items)).contains(shown.toString()).doesNotContain(future.toString(), ended.toString());
        JsonNode card = find(items, shown);
        assertThat(Instant.parse(card.get("opensAt").asString())).isCloseTo(now.minus(HOUR.multipliedBy(3)), within());
        assertThat(Instant.parse(card.get("closesAt").asString())).isCloseTo(now.plus(HOUR.multipliedBy(5)), within());
        assertThat(card.get("thumbnailUrl").asString()).isEqualTo("https://img/t.jpg");
    }

    @Test
    @DisplayName("고객 상세 — 상품은 묶은 순서 · 공개 · 판매 중 · 판매 준비된 것만(숨김 · 판매 중지 · 회차 취소 · 준비 전 빠짐), 일반 전환 · 마감도 싣고 할인가 · 정가 · 할인율 · 접수 단계 · Cache-Control public")
    void publicDetail() throws Exception {
        Instant now = Instant.now();
        UUID open = preorder("진행", now.minus(HOUR), now.plus(HOUR));
        jdbcTemplate.update("UPDATE products SET discount_rate = 10 WHERE id = ?", (Object) UuidBinary.toBytes(open));
        UUID closed = preorder("마감", now.minus(HOUR.multipliedBy(5)), now.minus(HOUR));
        UUID hidden = preorder("숨김", now.minus(HOUR), now.plus(HOUR));
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", (Object) UuidBinary.toBytes(hidden));
        UUID notReady = fixtures.product(categoryId, "PREORDER", "ACTIVE", "준비 전", "t");   // 회차 행 없음
        UUID converted = preorder("전환", now.minus(HOUR.multipliedBy(5)), now.minus(HOUR));
        UUID paused = preorder("판매 중지", now.minus(HOUR), now.plus(HOUR));
        UUID canceled = preorder("회차 취소", now.minus(HOUR), now.plus(HOUR));
        UUID promotionId = promotionId(create(body("상세", today, today, List.of("https://img/d.jpg"), open, closed, hidden, converted, paused, canceled)));
        jdbcTemplate.update("UPDATE products SET status = 'PAUSED' WHERE id = ?", (Object) UuidBinary.toBytes(paused));
        // 오픈 뒤 판매 중지 = 회차 취소 — 묶인 뒤에 취소돼도 카드에서 빠진다
        jdbcTemplate.update("UPDATE products SET status = 'PAUSED', campaign_canceled_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) UuidBinary.toBytes(canceled));
        jdbcTemplate.update("UPDATE products SET sale_mode = 'IN_STOCK' WHERE id = ?", (Object) UuidBinary.toBytes(converted));
        fixtures.stockReady(converted);
        jdbcTemplate.update("INSERT INTO promotion_products (promotion_id, position, product_id) VALUES (?, 6, ?)",
                UuidBinary.toBytes(promotionId), UuidBinary.toBytes(notReady));

        ResultActions response = mockMvc.perform(get("/api/v1/promotions/{id}", promotionId)).andExpect(status().isOk());
        assertThat(response.andReturn().getResponse().getHeader("Cache-Control")).matches("max-age=\\d, public");
        JsonNode detail = data(response);
        assertThat(detail.get("detailImageUrls").toString()).isEqualTo("[\"https://img/d.jpg\"]");
        JsonNode products = detail.get("products");
        assertThat(ids(products)).containsExactly(open.toString(), closed.toString(), converted.toString());
        assertThat(products.get(0).get("preorderStatus").asString()).isEqualTo("OPEN");
        assertThat(products.get(0).get("minPrice").decimalValue()).isEqualByComparingTo("1125000");   // 1,250,000 × 0.9
        assertThat(products.get(0).get("minListPrice").decimalValue()).isEqualByComparingTo("1250000");
        assertThat(products.get(1).get("preorderStatus").asString()).isEqualTo("CLOSED");
        assertThat(products.get(2).get("saleMode").asString()).isEqualTo("IN_STOCK");
        assertThat(products.get(2).get("opensAt").isNull()).isTrue();
        assertThat(Instant.parse(detail.get("closesAt").asString())).isCloseTo(now.plus(HOUR), within());
    }

    @Test
    @DisplayName("고객 상세는 기간 밖 · 없는 홍보면 404 — 로그인 없이 본다")
    void publicDetailOutsidePeriodIs404() throws Exception {
        UUID ended = promotionId(create(body("끝남", today.minusDays(3), today.minusDays(1), List.of(), preorder("E"))));
        UUID future = promotionId(create(body("예정", today.plusDays(1), today.plusDays(1), List.of(), preorder("F"))));
        mockMvc.perform(get("/api/v1/promotions/{id}", ended)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/promotions/{id}", future)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/promotions/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/promotions/{id}", ended).with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private UUID preorder(String title) {
        Instant now = Instant.now();
        return preorder(title, now.plus(HOUR), now.plus(HOUR.multipliedBy(3)));
    }

    /** 공개 · 판매 준비된 사전예약(옵션 1개 1,250,000원 · 회차). */
    private UUID preorder(String title, Instant opensAt, Instant closesAt) {
        UUID productId = fixtures.product(categoryId, "PREORDER", "ACTIVE", title, "t");
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE", new BigDecimal("1250000"));
        fixtures.campaign(productId, opensAt, closesAt);
        return productId;
    }

    /** 색상(블랙 · 화이트) × 용량(256GB · 512GB) 사전예약을 등록 API 로 만들고 회차를 넣는다. */
    private UUID registerPreorder() throws Exception {
        Instant opensAt = Instant.now().plus(HOUR.multipliedBy(2));
        String body = """
                { "categoryId": "%s", "saleMode": "PREORDER", "title": "축 있음", "brand": "APPLE", "modelName": "A0001", "visible": true,
                  "basePrice": 1000000,
                  "optionAxes": [ { "key": "color", "label": "색상", "values": [ { "value": "블랙" }, { "value": "화이트" } ] },
                                  { "key": "storage", "label": "용량", "values": [ { "value": "256GB" }, { "value": "512GB" } ] } ],
                  "campaign": { "opensAt": "%s", "closesAt": "%s" },
                  "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ],
                  "images": { "gallery": [ { "color": "블랙", "items": [ { "url": "https://img/b.jpg" } ] } ] } }
                """.formatted(categoryId, opensAt, opensAt.plus(Duration.ofDays(3)));
        UUID productId = UUID.fromString(data(mockMvc.perform(post("/api/v1/admin/products").header("Idempotency-Key", "k-" + ShopFixtures.unique())
                .contentType(MediaType.APPLICATION_JSON).content(body).with(admin())).andExpect(status().isCreated()))
                .get("registration").get("productId").asString());
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(3)));
        return productId;
    }

    private static String body(String name, LocalDate start, LocalDate end, List<String> details, UUID... productIds) {
        List<String> ids = new ArrayList<>();
        for (UUID id : productIds) {
            ids.add("\"" + id + "\"");
        }
        List<String> urls = new ArrayList<>();
        details.forEach(url -> urls.add("\"" + url + "\""));
        return """
                { "name": "%s", "startDate": "%s", "endDate": "%s", "thumbnailUrl": "https://img/t.jpg",
                  "detailImageUrls": [%s], "productIds": [%s] }
                """.formatted(name, start, end, String.join(", ", urls), String.join(", ", ids));
    }

    private ResultActions create(String body) throws Exception {
        return create("k-" + ShopFixtures.unique(), body);
    }

    private ResultActions create(String key, String body) throws Exception {
        return mockMvc.perform(post(ADMIN).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body).with(admin()));
    }

    private ResultActions replace(UUID promotionId, String body) throws Exception {
        return mockMvc.perform(put(ADMIN + "/{id}", promotionId).contentType(MediaType.APPLICATION_JSON).content(body).with(admin()));
    }

    private JsonNode linkable(UUID promotionId) throws Exception {
        MockHttpServletRequestBuilder request = get(ADMIN + "/linkable-products").param("size", "100").with(admin());
        if (promotionId != null) {
            request.param("promotionId", promotionId.toString());
        }
        return data(mockMvc.perform(request).andExpect(status().isOk())).get("items");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private static UUID promotionId(ResultActions actions) throws Exception {
        return UUID.fromString(data(actions).get("promotionId").asString());
    }

    private static List<String> ids(JsonNode products) {
        List<String> ids = new ArrayList<>();
        products.forEach(p -> ids.add(p.get("productId").asString()));
        return ids;
    }

    private static List<String> idsOf(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.has("promotionId") ? item.get("promotionId").asString() : item.get("productId").asString()));
        return ids;
    }

    private static JsonNode find(JsonNode items, UUID id) {
        for (JsonNode item : items) {
            String value = item.has("promotionId") ? item.get("promotionId").asString() : item.get("productId").asString();
            if (value.equals(id.toString())) {
                return item;
            }
        }
        throw new AssertionError("no " + id);
    }

    private static org.assertj.core.data.TemporalUnitOffset within() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(1, java.time.temporal.ChronoUnit.SECONDS);
    }

    private static void expectValidation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }
}
