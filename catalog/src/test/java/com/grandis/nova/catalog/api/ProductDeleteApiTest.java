package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.edit.ProductEditRequest;
import com.grandis.nova.catalog.edit.ProductEditService;
import com.grandis.nova.catalog.integration.DrawGiftClient;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.web.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 소프트 삭제(DELETE /api/v1/admin/products/{id}) — 행은 남고 비공개 · 삭제 시각이 찍히며, 관리자 · 회원 · 내부 조회 어디서도 없는 상품이 된다.
 * 드로우 증정품 확인(order 내부 API)은 대역이다 — 기본은 "진행 중 아님".
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@DisplayName("상품 소프트 삭제")
class ProductDeleteApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/admin/products";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ProductEditService editService;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean DrawGiftClient drawGifts;

    ShopFixtures fixtures;
    UUID categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
        given(drawGifts.getGift(any())).willAnswer(call -> ApiResponse.ok(new DrawGiftClient.DrawGift(call.getArgument(0), false)));
    }

    @Test
    @DisplayName("삭제 — 204, 행은 남고 deleted_at · 비공개. 관리자 상세 · 수정 · 다시 삭제는 404, 관리자 목록 · 회원 목록 · 회원 상세에서 빠진다")
    void deletedProductIsGoneEverywhere() throws Exception {
        String tag = "del" + ShopFixtures.unique();
        UUID productId = registerInStock(tag);
        fixtures.stockReady(productId);
        UUID kept = registerInStock(tag);   // 같은 태그의 남는 상품 — 건수가 0 이면 목록 질의를 건너뛰므로 목록 · 건수를 따로 태운다
        assertThat(memberListIds(tag)).as("대조군 — 삭제 전에는 회원 목록에 있다").containsExactly(productId);
        assertThat(adminListIds(tag)).containsExactlyInAnyOrder(productId, kept);
        assertThat(adminListTotal(tag)).isEqualTo(2);

        deleteProduct(productId).andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT visible, deleted_at FROM products WHERE id = ?", (Object) UuidBinary.toBytes(productId));
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("visible")).isEqualTo(false);
        mockMvc.perform(admin(get(PATH + "/{id}", productId))).andExpect(status().isNotFound());
        mockMvc.perform(admin(patch(PATH + "/{id}", productId)).content("{ \"title\": \"고침\" }")).andExpect(status().isNotFound());
        mockMvc.perform(admin(patch(PATH + "/{id}/visibility", productId)).content("{ \"visible\": true }")).andExpect(status().isNotFound());
        deleteProduct(productId).andExpect(status().isNotFound());
        assertThat(adminListIds(tag)).containsExactly(kept);
        assertThat(adminListTotal(tag)).as("건수도 같은 조건").isEqualTo(1);
        assertThat(memberListIds(tag)).isEmpty();
        mockMvc.perform(get("/api/v1/products/{id}", productId)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("내부 조회 — 상품 옵션은 404 PRODUCT_NOT_FOUND, 옵션 묶음 조회에서는 그 옵션이 빠진다(호출자는 판매 종료로 본다). 대조군: 다른 상품의 옵션은 그대로")
    void internalReadsTreatDeletedAsMissing() throws Exception {
        UUID deleted = registerInStock("int" + ShopFixtures.unique());
        UUID kept = registerInStock("int" + ShopFixtures.unique());
        UUID deletedOption = firstOption(deleted);
        UUID keptOption = firstOption(kept);
        mockMvc.perform(member(get("/internal/products/{id}/options", deleted))).andExpect(status().isOk());

        deleteProduct(deleted).andExpect(status().isNoContent());

        mockMvc.perform(member(get("/internal/products/{id}/options", deleted))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        List<String> ids = new ArrayList<>();
        data(mockMvc.perform(member(get("/internal/options").param("ids", deletedOption + "," + keptOption))).andExpect(status().isOk()))
                .get("items").forEach(item -> ids.add(item.get("optionId").asString()));
        assertThat(ids).containsExactly(keptOption.toString());
    }

    @Test
    @DisplayName("증정품 후보 · 홍보 연결 후보에서 빠진다 — 묶여 있던 홍보의 관리자 화면에서도 빠지고, 다시 보내면 400(없는 상품)")
    void adminPickersAndPromotionsDropDeleted() throws Exception {
        String tag = "gift" + ShopFixtures.unique();
        UUID gift = registerInStock(tag);
        fixtures.stockReady(gift);
        assertThat(giftCandidateIds(tag)).as("대조군").containsExactly(gift);
        UUID preorder = registerPreorder(Instant.now().plus(Duration.ofDays(2)));
        UUID promotionId = promotionWith(preorder);
        assertThat(linkedIds(promotionId)).as("대조군").containsExactly(preorder);
        assertThat(linkableIds(promotionId)).as("대조군").contains(preorder);

        deleteProduct(gift).andExpect(status().isNoContent());
        deleteProduct(preorder).andExpect(status().isNoContent());

        assertThat(giftCandidateIds(tag)).isEmpty();
        assertThat(linkedIds(promotionId)).isEmpty();
        assertThat(linkableIds(promotionId)).doesNotContain(preorder);
        mockMvc.perform(admin(put("/api/v1/admin/promotions/{id}", promotionId)).content("""
                        { "name": "홍보", "startDate": "2026-10-01", "endDate": "2026-12-31", "thumbnailUrl": "https://img/t.jpg",
                          "detailImageUrls": [], "productIds": ["%s"] }""".formatted(preorder)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("productIds[0]"));
    }

    @Test
    @DisplayName("사전예약 — 오픈 전(3분 동결 밖)이면 삭제되고 PREORDER_PRODUCT_CHANGED(비공개 · 번호 +1)를 적는다. 일반 상품은 이벤트가 없다")
    void preorderDeleteNotifiesPreorder() throws Exception {
        Instant opensAt = Instant.now().plus(Duration.ofHours(2));
        UUID preorder = registerPreorder(opensAt);
        fixtures.campaign(preorder, opensAt, opensAt.plus(Duration.ofDays(1)));
        long version = listingVersionOf(preorder);
        UUID inStock = registerInStock("ev" + ShopFixtures.unique());

        deleteProduct(preorder).andExpect(status().isNoContent());
        deleteProduct(inStock).andExpect(status().isNoContent());

        List<JsonNode> changed = changedPayloads(preorder);
        assertThat(changed).hasSize(1);
        assertThat(changed.getFirst().get("visible").asBoolean()).isFalse();
        assertThat(changed.getFirst().get("listingVersion").asLong()).isEqualTo(version + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_outbox_events WHERE aggregate_id = ? AND event_type <> 'IN_STOCK_PRODUCT_REGISTERED'",
                Long.class, (Object) UuidBinary.toBytes(inStock))).isZero();
    }

    @Test
    @DisplayName("사전예약 진행 중(오픈 3분 전 ~ 마감 전, 회차 취소 전)은 409 — 아무것도 바뀌지 않는다. 마감 뒤 · 회차 취소 뒤 · 회차 없음은 삭제된다")
    void preorderInProgressIsRejected() throws Exception {
        Instant now = Instant.now();
        UUID open = preorderWithCampaign(now.minus(Duration.ofMinutes(10)), now.plus(Duration.ofDays(1)));
        UUID frozen = preorderWithCampaign(now.plus(Duration.ofMinutes(2)), now.plus(Duration.ofDays(1)));
        for (UUID productId : List.of(open, frozen)) {
            deleteProduct(productId).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
            assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NULL AND visible = 1 FROM products WHERE id = ?", Boolean.class,
                    (Object) UuidBinary.toBytes(productId))).isTrue();
            assertThat(changedPayloads(productId)).isEmpty();
        }

        UUID closed = preorderWithCampaign(now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        UUID canceled = preorderWithCampaign(now.minus(Duration.ofMinutes(10)), now.plus(Duration.ofDays(1)));
        jdbcTemplate.update("UPDATE products SET status = 'PAUSED', campaign_canceled_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) UuidBinary.toBytes(canceled));
        UUID noCampaign = registerPreorder(now.plus(Duration.ofDays(2)));   // 회차 행이 없다(등록 이벤트 처리 전)
        for (UUID productId : List.of(closed, canceled, noCampaign)) {
            deleteProduct(productId).andExpect(status().isNoContent());
        }
    }

    @Test
    @DisplayName("반영 전 드로우의 증정품이면 409 · order 장애는 503 · 물은 상품과 다른 응답은 500 — 셋 다 아무것도 바뀌지 않는다. 없는 상품 · 이미 삭제한 상품은 order 를 부르지 않고 404")
    void drawGiftCheck() throws Exception {
        UUID inDraw = registerInStock("dr" + ShopFixtures.unique());
        given(drawGifts.getGift(inDraw)).willReturn(ApiResponse.ok(new DrawGiftClient.DrawGift(inDraw, true)));
        deleteProduct(inDraw).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));

        UUID orderDown = registerInStock("dr" + ShopFixtures.unique());
        given(drawGifts.getGift(orderDown)).willThrow(new ResourceAccessException("connection refused"));
        deleteProduct(orderDown).andExpect(status().isServiceUnavailable());

        UUID wrong = registerInStock("dr" + ShopFixtures.unique());
        given(drawGifts.getGift(wrong)).willReturn(ApiResponse.ok(new DrawGiftClient.DrawGift(UUID.randomUUID(), false)));
        deleteProduct(wrong).andExpect(status().isInternalServerError());

        for (UUID productId : List.of(inDraw, orderDown, wrong)) {
            assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NULL FROM products WHERE id = ?", Boolean.class,
                    (Object) UuidBinary.toBytes(productId))).isTrue();
        }
        UUID missing = UUID.randomUUID();
        deleteProduct(missing).andExpect(status().isNotFound());
        verify(drawGifts, never()).getGift(missing);
        UUID gone = registerInStock("dr" + ShopFixtures.unique());
        deleteProduct(gone).andExpect(status().isNoContent());
        deleteProduct(gone).andExpect(status().isNotFound());
        verify(drawGifts, times(1)).getGift(gone);   // 이미 삭제한 상품도 order 를 다시 부르지 않는다
    }

    @Test
    @DisplayName("회원 토큰은 403")
    void memberIsForbidden() throws Exception {
        UUID productId = registerInStock("auth" + ShopFixtures.unique());
        mockMvc.perform(delete(PATH + "/{id}", productId).with(user("657").roles("USER"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DB 가 지킨다 — 삭제한 상품을 공개로 되돌리는 UPDATE 는 CHECK(ck_product_deleted_hidden)에 막힌다")
    void deletedProductCannotBeVisible() throws Exception {
        UUID productId = registerInStock("ck" + ShopFixtures.unique());
        deleteProduct(productId).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("UPDATE products SET visible = 1 WHERE id = ?",
                        (Object) UuidBinary.toBytes(productId)))
                .hasMessageContaining("ck_product_deleted_hidden");
    }

    @Test
    @DisplayName("삭제가 커밋되기를 기다린 수정은 404 — 잠근 뒤 삭제가 커밋한 행을 다시 읽는다. 대조군: 잠그기만 하고 삭제하지 않으면 수정된다")
    void editWaitingBehindDeleteSeesItGone() throws Exception {
        for (boolean deleteWhileHolding : List.of(false, true)) {
            UUID productId = registerInStock("race" + ShopFixtures.unique());
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbcTemplate.queryForList("SELECT id FROM products WHERE id = ? FOR UPDATE", (Object) UuidBinary.toBytes(productId));
                    if (deleteWhileHolding) {
                        jdbcTemplate.update("UPDATE products SET visible = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) UuidBinary.toBytes(productId));
                    }
                    locked.countDown();
                    await(release);
                }));
                await(locked);
                Future<Throwable> edit = pool.submit(() -> {
                    try {
                        editService.editProduct(productId, new ProductEditRequest("고침", null, null, null, null, null, null, null));
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                });
                awaitBlocked("%from products %for update%");
                assertThat(edit.isDone()).as("수정은 잠금을 기다린다").isFalse();
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                Throwable result = edit.get(10, TimeUnit.SECONDS);
                if (deleteWhileHolding) {
                    assertThat(result).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) result).errorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                } else {
                    assertThat(result).as("대조군").isNull();
                }
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("삭제가 커밋되기를 기다린 홍보 저장은 그 상품을 400 으로 거른다. 대조군: 잠그기만 하고 삭제하지 않으면 200")
    void promotionSaveWaitingBehindDeleteRejectsIt() throws Exception {
        for (boolean deleteWhileHolding : List.of(false, true)) {
            UUID productId = registerPreorder(Instant.now().plus(Duration.ofDays(2)));
            UUID promotionId = promotionWith(productId);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbcTemplate.queryForList("SELECT id FROM products WHERE id = ? FOR UPDATE", (Object) UuidBinary.toBytes(productId));
                    if (deleteWhileHolding) {
                        jdbcTemplate.update("UPDATE products SET visible = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) UuidBinary.toBytes(productId));
                    }
                    locked.countDown();
                    await(release);
                }));
                await(locked);
                Future<Integer> save = pool.submit(() -> mockMvc.perform(admin(put("/api/v1/admin/promotions/{id}", promotionId)).content("""
                                { "name": "홍보", "startDate": "2026-10-01", "endDate": "2026-12-31", "thumbnailUrl": "https://img/t.jpg",
                                  "detailImageUrls": [], "productIds": ["%s"] }""".formatted(productId)))
                        .andReturn().getResponse().getStatus());
                awaitBlocked("%from products %for share%");   // 검증의 공유 잠금 읽기가 상품 행에서 기다린다(잠그지 않는 검증은 여기 오지 않는다)
                assertThat(save.isDone()).as("홍보 저장은 상품 행을 기다린다").isFalse();
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                assertThat(save.get(10, TimeUnit.SECONDS)).isEqualTo(deleteWhileHolding ? 400 : 200);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

    /** 다른 연결에서 그 모양의 쿼리가 두 번 잇달아 실행 중으로 보일 때까지(잠금 대기) 기다린다. 30초 안에 안 보이면 실패. */
    private void awaitBlocked(String likePattern) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int seen = 0;
        while (System.nanoTime() < deadline) {
            Long blocked = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.PROCESSLIST
                     WHERE COMMAND = 'Query' AND ID <> CONNECTION_ID() AND LOWER(INFO) LIKE ?
                    """, Long.class, likePattern);
            seen = blocked != null && blocked > 0 ? seen + 1 : 0;
            if (seen >= 2) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("30초 안에 잠금에서 기다리는 쿼리가 보이지 않았다: " + likePattern);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private UUID preorderWithCampaign(Instant opensAt, Instant closesAt) throws Exception {
        UUID productId = registerPreorder(Instant.now().plus(Duration.ofDays(2)));
        fixtures.campaign(productId, opensAt, closesAt);
        return productId;
    }

    private UUID registerInStock(String tag) throws Exception {
        return register("IN_STOCK", tag, """
                "combinations": [ { "selections": { "color": "블랙" }, "stock": 3 }, { "selections": { "color": "화이트" }, "stock": 3 } ],
                """);
    }

    private UUID registerPreorder(Instant opensAt) throws Exception {
        return register("PREORDER", "pre" + ShopFixtures.unique(), """
                "campaign": { "opensAt": "%s", "closesAt": "%s" },
                "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ],
                """.formatted(opensAt, opensAt.plus(Duration.ofDays(3))));
    }

    private UUID register(String saleMode, String tag, String extra) throws Exception {
        String body = """
                {
                  "categoryId": "%s", "saleMode": "%s", "title": "Nova 1", "brand": "APPLE", "modelName": "A0001", "tags": "%s", "visible": true,
                  "basePrice": 1000000,
                  "optionAxes": [ { "key": "color", "label": "색상", "values": [ { "value": "블랙" }, { "value": "화이트" } ] } ],
                  %s
                  "images": { "gallery": [ { "color": "블랙", "items": [ { "url": "https://img/b.jpg" } ] }, { "color": "화이트", "items": [ { "url": "https://img/w.jpg" } ] } ] }
                }
                """.formatted(categoryId, saleMode, tag, extra);
        ResultActions created = mockMvc.perform(admin(post(PATH)).header("Idempotency-Key", "k-" + ShopFixtures.unique()).content(body))
                .andExpect(status().isCreated());
        return UUID.fromString(data(created).get("registration").get("productId").asString());
    }

    private UUID promotionWith(UUID productId) {
        UUID promotionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO promotions (id, idempotency_key, name, start_date, end_date, thumbnail_url, detail_image_urls, created_at, updated_at)
                VALUES (?, ?, '홍보', '2026-10-01', '2026-12-31', 'https://img/t.jpg', JSON_ARRAY(), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, UuidBinary.toBytes(promotionId), "k-" + ShopFixtures.unique());
        jdbcTemplate.update("INSERT INTO promotion_products (promotion_id, position, product_id) VALUES (?, 0, ?)",
                UuidBinary.toBytes(promotionId), UuidBinary.toBytes(productId));
        return promotionId;
    }

    private ResultActions deleteProduct(UUID productId) throws Exception {
        return mockMvc.perform(admin(delete(PATH + "/{id}", productId)));
    }

    private UUID firstOption(UUID productId) {
        return UuidBinary.fromBytes(jdbcTemplate.queryForObject("SELECT id FROM product_options WHERE product_id = ? ORDER BY id LIMIT 1",
                byte[].class, (Object) UuidBinary.toBytes(productId)));
    }

    private long listingVersionOf(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT listing_version FROM products WHERE id = ?", Long.class, (Object) UuidBinary.toBytes(productId));
    }

    private List<JsonNode> changedPayloads(UUID productId) {
        return jdbcTemplate.queryForList("SELECT payload FROM catalog_outbox_events WHERE aggregate_id = ? AND event_type = 'PREORDER_PRODUCT_CHANGED' ORDER BY id",
                String.class, (Object) UuidBinary.toBytes(productId)).stream().map(JSON::readTree).toList();
    }

    private List<UUID> memberListIds(String tag) throws Exception {
        return ids(data(mockMvc.perform(get("/api/v1/products").param("q", tag).param("size", "100")).andExpect(status().isOk())).get("items"), "productId");
    }

    private List<UUID> adminListIds(String tag) throws Exception {
        return ids(data(mockMvc.perform(admin(get(PATH)).param("q", tag).param("size", "100")).andExpect(status().isOk())).get("items"), "productId");
    }

    private long adminListTotal(String tag) throws Exception {
        return data(mockMvc.perform(admin(get(PATH)).param("q", tag).param("size", "100")).andExpect(status().isOk())).get("total").asLong();
    }

    private List<UUID> giftCandidateIds(String tag) throws Exception {
        return ids(data(mockMvc.perform(admin(get(PATH + "/gift-candidates")).param("q", tag).param("size", "100")).andExpect(status().isOk()))
                .get("items"), "productId");
    }

    private List<UUID> linkedIds(UUID promotionId) throws Exception {
        return ids(data(mockMvc.perform(admin(get("/api/v1/admin/promotions/{id}", promotionId))).andExpect(status().isOk())).get("products"), "productId");
    }

    private List<UUID> linkableIds(UUID promotionId) throws Exception {
        return ids(data(mockMvc.perform(admin(get("/api/v1/admin/promotions/linkable-products")).param("promotionId", promotionId.toString())
                .param("size", "100")).andExpect(status().isOk())).get("items"), "productId");
    }

    private static List<UUID> ids(JsonNode items, String field) {
        List<UUID> ids = new ArrayList<>();
        items.forEach(item -> ids.add(UUID.fromString(item.get(field).asString())));
        return ids;
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON).with(user("admin").roles("ADMIN"));
    }

    private static MockHttpServletRequestBuilder member(MockHttpServletRequestBuilder builder) {
        return builder.with(user("657").roles("USER"));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }
}
