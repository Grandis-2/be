package com.grandis.nova.order.draw;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.CaptureRequest;
import com.grandis.nova.order.client.payment.ConfirmReply;
import com.grandis.nova.order.client.payment.ConfirmRequest;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.client.payment.PaymentAttempt;
import com.grandis.nova.order.client.payment.PaymentClient;
import com.grandis.nova.order.client.payment.PaymentConfirmClient;
import com.grandis.nova.order.event.OrderEventDispatcher;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원의 럭키 드로우 — 회차 조회 · 응모 · 내 응모 · 응모비 결제(준비 · 승인 · 결과 이벤트). MySQL 위에서 돌고 payment 내부 API 만 대역이다.
 * 회차는 표에 직접 심는다(관리자 만들기는 AdminDrawApiTest).
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class DrawEntryApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    // 사용자가 보낸 액세스 토큰 자리. payment 에 그대로 전달돼야 한다. 실제 토큰 모양이 아니다.
    static final String SESSION = "draw-entry-api-test";
    static final String TOSS_ORDER_ID = "draw_6f1c2d3e4b5a";
    static final String PAYMENT = "tgen_draw_entry_test";
    static final BigDecimal FEE = new BigDecimal("300");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OrderEventDispatcher dispatcher;
    @MockitoBean PaymentClient paymentClient;
    @MockitoBean PaymentConfirmClient confirmClient;

    OrderFixtures fixtures;
    UUID customerId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        customerId = fixtures.customer();
    }

    // ── 회차 조회 ──

    @Test
    @DisplayName("회차 목록 · 상세는 로그인 없이 본다")
    void drawsAreReadableWithoutLogin() throws Exception {
        UUID draw = openDraw();

        mockMvc.perform(get("/api/v1/draws/{id}", draw)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.drawId").value(draw.toString()))
                .andExpect(jsonPath("$.data.phase").value("OPEN"));
        mockMvc.perform(get("/api/v1/draws")).andExpect(status().isOk());
    }

    // ── 응모 ──

    @Test
    @DisplayName("응모하면 201 · 결제 대기 · 배송지 저장. 다시 응모하면 그 응모를 200 으로 — 배송지를 바꾸지 않는다")
    void enterOncePerMember() throws Exception {
        UUID draw = openDraw();

        JsonNode entry = data(enter(customerId, draw, shipTo("홍길동")).andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/draws/" + draw + "/entries/me")));
        assertThat(entry.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(entry.get("shipTo").get("name").asString()).isEqualTo("홍길동");

        JsonNode again = data(enter(customerId, draw, shipTo("김철수")).andExpect(status().isOk()));
        assertThat(again.get("entryId").asString()).isEqualTo(entry.get("entryId").asString());
        assertThat(again.get("shipTo").get("name").asString()).isEqualTo("홍길동");
        assertThat(entriesOf(draw)).isEqualTo(1);

        JsonNode mine = data(mockMvc.perform(get("/api/v1/draws/{id}/entries/me", draw).with(TestAuth.customer(customerId)))
                .andExpect(status().isOk()));
        assertThat(mine.get("entryId").asString()).isEqualTo(entry.get("entryId").asString());
    }

    @Test
    @DisplayName("새 응모는 응모 기간에만 — 시작 전 · 마감 뒤 409 DRAW_NOT_OPEN, 없는 회차 404. 마감 전에 한 응모는 마감 뒤에도 그대로 돌려준다")
    void newEntriesOnlyWhileOpen() throws Exception {
        Instant now = Instant.now();
        UUID scheduled = draw(now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)));
        UUID closed = draw(now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));

        enter(customerId, scheduled, shipTo("홍길동")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DRAW_NOT_OPEN"));
        enter(customerId, closed, shipTo("홍길동")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DRAW_NOT_OPEN"));
        enter(customerId, UUID.randomUUID(), shipTo("홍길동")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DRAW_NOT_FOUND"));
        assertThat(entriesOf(scheduled) + entriesOf(closed)).isZero();

        insertEntry(closed, customerId, "AWAITING_PAYMENT", null);
        enter(customerId, closed, shipTo("홍길동")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("배송지가 없거나 비면 400 · 내 응모가 없으면 404 · 남의 응모는 보이지 않는다")
    void entryValidationAndOwnership() throws Exception {
        UUID draw = openDraw();

        mockMvc.perform(post("/api/v1/draws/{id}/entries", draw).with(TestAuth.customer(customerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.violations[0].field").value("shipTo"));
        enter(customerId, draw, shipTo(" ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("shipTo.name"));

        enter(fixtures.customer(), draw, shipTo("남")).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/draws/{id}/entries/me", draw).with(TestAuth.customer(customerId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DRAW_ENTRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("응모 · 결제는 회원만 — 익명 401, 관리자 403")
    void entriesAreMembersOnly() throws Exception {
        UUID draw = openDraw();

        mockMvc.perform(post("/api/v1/draws/{id}/entries", draw).contentType(MediaType.APPLICATION_JSON).content(shipTo("홍길동")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/draws/{id}/entries", draw).with(TestAuth.admin()).contentType(MediaType.APPLICATION_JSON)
                .content(shipTo("홍길동"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/draws/{id}/entries/me/payment-attempts", draw)).andExpect(status().isUnauthorized());
    }

    /**
     * 같은 회원의 응모가 겹치면 유일 키가 하나만 남기고 늦은 쪽은 먼저 들어간 응모를 200 으로 받는다. 결정적으로 본다: 앞선 쪽이 응모 행을
     * 넣은 채 커밋하지 않고, 늦은 쪽이 그 유일 키에서 기다리는 것을 확인한 뒤 커밋한다.
     */
    @Test
    @DisplayName("같은 회원의 응모가 겹쳐도 응모는 하나 — 늦은 쪽은 먼저 들어간 응모를 200")
    void concurrentEntriesOfOneMemberKeepOne() throws Exception {
        UUID draw = openDraw();
        UUID earlier = UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            insertEntry(earlier, draw, customerId, "AWAITING_PAYMENT", null);
            inserted.countDown();
            await(commit);
        }));
        await(inserted);
        CompletableFuture<String> later = CompletableFuture.supplyAsync(() -> {
            try {
                return enter(customerId, draw, shipTo("홍길동")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitForQuery("insert into draw_entries%");
        commit.countDown();
        first.get(20, TimeUnit.SECONDS);

        assertThat(JSON.readTree(later.get(20, TimeUnit.SECONDS)).get("data").get("entryId").asString()).isEqualTo(earlier.toString());
        assertThat(entriesOf(draw)).isEqualTo(1);
    }

    // ── 응모비 결제 준비 ──

    @Test
    @DisplayName("결제 준비는 응모비 · 회차 제목으로 결제창을 연다 — 대상 DRAW_ENTRY, 사용자 토큰 전달")
    void prepareOpensCaptureForEntryFee() throws Exception {
        UUID draw = openDraw();
        UUID entry = insertEntry(draw, customerId, "AWAITING_PAYMENT", null);
        given(paymentClient.openCapture(any(), any())).willReturn(ApiResponse.ok(new PaymentAttempt(TOSS_ORDER_ID, FEE)));

        prepare(customerId, draw).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tossOrderId").value(TOSS_ORDER_ID))
                .andExpect(jsonPath("$.data.amount").value(300))
                .andExpect(jsonPath("$.data.orderName").value("드로우 회차"));

        verify(paymentClient).openCapture(new CaptureRequest("DRAW_ENTRY", entry, FEE), BearerTokens.value(SESSION));
        assertThat(statusOf(entry)).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    @DisplayName("결제 준비 거절 — 응모 안 함 404, 결제 대기 아님 409 DRAW_ENTRY_NOT_PAYABLE, 마감 뒤 409 DRAW_NOT_OPEN. payment 를 부르지 않는다")
    void prepareRefusals() throws Exception {
        UUID draw = openDraw();
        prepare(customerId, draw).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DRAW_ENTRY_NOT_FOUND"));

        insertEntry(draw, customerId, "AUTHORIZING", TOSS_ORDER_ID);
        prepare(customerId, draw).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DRAW_ENTRY_NOT_PAYABLE"));

        Instant now = Instant.now();
        UUID closed = draw(now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        insertEntry(closed, customerId, "AWAITING_PAYMENT", null);
        prepare(customerId, closed).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DRAW_NOT_OPEN"));

        verifyNoInteractions(paymentClient);
    }

    // ── 응모비 결제 승인 ──

    @Test
    @DisplayName("승인되면 결제 완료(PAID) — 대상 DRAW_ENTRY · 응모비로 승인시킨다. 다시 보내면 payment 를 부르지 않고 APPROVED")
    void approvedEntryIsPaid() throws Exception {
        UUID draw = openDraw();
        UUID entry = insertEntry(draw, customerId, "AWAITING_PAYMENT", null);
        paymentAnswers(reply(ConfirmReply.Result.APPROVED, null));

        confirm(customerId, draw, FEE).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPROVED"))
                .andExpect(jsonPath("$.data.entryStatus").value("PAID"));

        assertThat(statusOf(entry)).isEqualTo("PAID");
        verify(confirmClient).confirm(eq(TOSS_ORDER_ID), argThat(r -> r.startAllowed() && "DRAW_ENTRY".equals(r.targetType())
                && entry.equals(r.targetId()) && FEE.compareTo(r.amount()) == 0), eq(BearerTokens.value(SESSION)));

        confirm(customerId, draw, FEE).andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("APPROVED"));
        verify(confirmClient, times(2)).confirm(any(), any(), any());
    }

    @Test
    @DisplayName("거절되면 결제 대기로 돌아가 다시 결제할 수 있다 — DECLINED · 사유")
    void declinedEntryReturnsToAwaitingPayment() throws Exception {
        UUID draw = openDraw();
        UUID entry = insertEntry(draw, customerId, "AWAITING_PAYMENT", null);
        paymentAnswers(reply(ConfirmReply.Result.DECLINED, DeclineReason.CARD_REJECTED));

        confirm(customerId, draw, FEE).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DECLINED"))
                .andExpect(jsonPath("$.data.entryStatus").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.declineReason").value("CARD_REJECTED"));

        assertThat(entryRow(entry)).containsEntry("status", "AWAITING_PAYMENT").containsEntry("authorizing_provider_order_id", null);
    }

    @Test
    @DisplayName("결과를 모르면 승인 중(PENDING)으로 두고, 결과 이벤트가 결제 완료로 반영한다. 늦게 온 다른 결제창의 거절은 되돌리지 않는다")
    void pendingEntryIsSettledByResultEvent() throws Exception {
        UUID draw = openDraw();
        UUID entry = insertEntry(draw, customerId, "AWAITING_PAYMENT", null);
        paymentAnswers(reply(ConfirmReply.Result.PENDING, null));

        confirm(customerId, draw, FEE).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PENDING"))
                .andExpect(jsonPath("$.data.entryStatus").value("AUTHORIZING"));
        assertThat(entryRow(entry)).containsEntry("status", "AUTHORIZING").containsEntry("authorizing_provider_order_id", TOSS_ORDER_ID);

        dispatcher.dispatch(settled(entry, "other_window_01", "DECLINED", "CARD_REJECTED"));
        assertThat(statusOf(entry)).as("다른 결제창의 거절").isEqualTo("AUTHORIZING");

        dispatcher.dispatch(settled(entry, TOSS_ORDER_ID, "APPROVED", null));
        assertThat(entryRow(entry)).containsEntry("status", "PAID").containsEntry("authorizing_provider_order_id", null);

        dispatcher.dispatch(settled(entry, TOSS_ORDER_ID, "DECLINED", "PAYMENT_EXPIRED"));
        assertThat(statusOf(entry)).as("결제 완료 뒤 거절은 반영하지 않는다").isEqualTo("PAID");
    }

    @Test
    @DisplayName("결제창 만료 이벤트는 승인 중인 응모를 결제 대기로 되돌린다")
    void expiredWindowRevertsAuthorizingEntry() {
        UUID draw = openDraw();
        UUID entry = insertEntry(draw, customerId, "AUTHORIZING", TOSS_ORDER_ID);

        dispatcher.dispatch(settled(entry, TOSS_ORDER_ID, "DECLINED", "PAYMENT_EXPIRED"));

        assertThat(entryRow(entry)).containsEntry("status", "AWAITING_PAYMENT").containsEntry("authorizing_provider_order_id", null);
    }

    @Test
    @DisplayName("결제 대기에 온 승인도 결제 완료로 — 응모는 취소가 없어 그 돈은 그 응모의 것이다")
    void approvalForAwaitingEntryIsAccepted() {
        UUID draw = openDraw();
        UUID entry = insertEntry(draw, customerId, "AWAITING_PAYMENT", null);

        dispatcher.dispatch(settled(entry, TOSS_ORDER_ID, "APPROVED", null));

        assertThat(statusOf(entry)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("금액이 응모비와 다르면 409 PAYMENT_AMOUNT_MISMATCH — payment 를 부르지 않고 응모도 그대로")
    void amountMismatchChangesNothing() throws Exception {
        UUID draw = openDraw();
        UUID entry = insertEntry(draw, customerId, "AWAITING_PAYMENT", null);

        confirm(customerId, draw, new BigDecimal("100")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_AMOUNT_MISMATCH"));

        verifyNoInteractions(confirmClient);
        assertThat(statusOf(entry)).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    @DisplayName("마감 뒤에는 결제를 시작하지 않는다 — 결제 대기면 409 DRAW_NOT_OPEN, 승인 중 재요청은 시작 금지로 결과만 회수한다")
    void noNewStartAfterClose() throws Exception {
        Instant now = Instant.now();
        UUID closed = draw(now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        insertEntry(closed, customerId, "AWAITING_PAYMENT", null);
        confirm(customerId, closed, FEE).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DRAW_NOT_OPEN"));
        verifyNoInteractions(confirmClient);

        UUID other = fixtures.customer();
        UUID authorizing = insertEntry(closed, other, "AUTHORIZING", TOSS_ORDER_ID);
        paymentAnswers(reply(ConfirmReply.Result.APPROVED, null));
        confirm(other, closed, FEE).andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("APPROVED"));

        verify(confirmClient).confirm(eq(TOSS_ORDER_ID), argThat(r -> !r.startAllowed() && authorizing.equals(r.targetId())), any());
        assertThat(statusOf(authorizing)).as("시작한 결제는 마감 뒤에 확정돼도 결제 완료").isEqualTo("PAID");
    }

    @Test
    @DisplayName("payment 가 결제창을 모르면(시작될 수 없음) 결제 대기로 되돌리고 404 PAYMENT_ATTEMPT_NOT_FOUND")
    void unknownWindowRevertsToAwaitingPayment() throws Exception {
        UUID draw = openDraw();
        UUID entry = insertEntry(draw, customerId, "AUTHORIZING", TOSS_ORDER_ID);
        HttpClientErrorException rejected = rejection(HttpStatus.NOT_FOUND, "PAYMENT_ATTEMPT_NOT_FOUND");
        given(confirmClient.confirm(any(), any(), any())).willThrow(rejected);

        confirm(customerId, draw, FEE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ATTEMPT_NOT_FOUND"));

        assertThat(entryRow(entry)).containsEntry("status", "AWAITING_PAYMENT").containsEntry("authorizing_provider_order_id", null);
    }

    @Test
    @DisplayName("승인 중인 응모에 다른 결제창으로 오면 확인 중(PENDING) — payment 를 부르지 않는다")
    void otherWindowWhileAuthorizingIsPending() throws Exception {
        UUID draw = openDraw();
        insertEntry(draw, customerId, "AUTHORIZING", "earlier_window_1");

        confirm(customerId, draw, FEE).andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("PENDING"));

        verifyNoInteractions(confirmClient);
    }

    // ── 도우미 ──

    private UUID openDraw() {
        Instant now = Instant.now();
        return draw(now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)));
    }

    private UUID draw(Instant opens, Instant closes) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_campaigns (id, idempotency_key, product_id, option_id, title, product_title_snapshot, option_title_snapshot,
                                            entry_fee, winner_count, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, '드로우 회차', 'p', 'o', ?, 1, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(id), id.toString(), bytes(product.productId()), bytes(product.optionIds().getFirst()), FEE, utc(opens), utc(closes));
        return id;
    }

    /** 앱은 datetime 을 UTC 로 읽는다(hibernate.jdbc.time_zone). Timestamp 로 넘기면 JVM 시간대로 바뀌어 단계가 어긋난다. */
    private static String utc(Instant at) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC).format(at);
    }

    private UUID insertEntry(UUID draw, UUID customer, String status, String providerOrderId) {
        return insertEntry(UUID.randomUUID(), draw, customer, status, providerOrderId);
    }

    private UUID insertEntry(UUID id, UUID draw, UUID customer, String status, String providerOrderId) {
        jdbcTemplate.update("""
                INSERT INTO draw_entries (id, campaign_id, customer_id, status, authorizing_provider_order_id, ship_to_name, ship_to_phone,
                                          ship_to_postal_code, ship_to_line1, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '홍길동', '010-0000-0000', '04524', '서울시 중구 세종대로 110', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(id), bytes(draw), bytes(customer), status, providerOrderId);
        return id;
    }

    private static String shipTo(String name) {
        return """
                {"shipTo":{"name":"%s","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}}""".formatted(name);
    }

    private ResultActions enter(UUID customer, UUID draw, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/draws/{id}/entries", draw).with(TestAuth.customer(customer))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions prepare(UUID customer, UUID draw) throws Exception {
        return mockMvc.perform(withSession(post("/api/v1/draws/{id}/entries/me/payment-attempts", draw).with(TestAuth.customer(customer))));
    }

    private ResultActions confirm(UUID customer, UUID draw, BigDecimal amount) throws Exception {
        return mockMvc.perform(withSession(post("/api/v1/draws/{id}/entries/me/payment-attempts/{toss}/confirm", draw, TOSS_ORDER_ID)
                .with(TestAuth.customer(customer)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"%s\",\"amount\":%s}".formatted(PAYMENT, amount))));
    }

    private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request) {
        return request.header(BearerTokens.HEADER, BearerTokens.value(SESSION));
    }

    /** payment 대역. 결제창 확인(시작 금지 + 확보)에는 "시작 전"(PENDING)으로 답하고, 그 밖의 호출은 reply 로 답한다. */
    private void paymentAnswers(ApiResponse<ConfirmReply> reply) {
        given(confirmClient.confirm(any(), any(), any())).willAnswer((Answer<ApiResponse<ConfirmReply>>) call ->
                isCheck(call) ? reply(ConfirmReply.Result.PENDING, null) : reply);
    }

    /** request 가 null 이면 다시 스텁하는 중이다. */
    private static boolean isCheck(InvocationOnMock call) {
        ConfirmRequest request = call.getArgument(1);
        return request != null && request.reserve();
    }

    private static ApiResponse<ConfirmReply> reply(ConfirmReply.Result result, DeclineReason reason) {
        return ApiResponse.ok(new ConfirmReply(result, reason));
    }

    private HttpClientErrorException rejection(HttpStatus status, String code) {
        String body = """
                {"success":false,"data":null,"error":{"code":"%s","message":"m","details":null}}
                """.formatted(code);
        HttpClientErrorException rejected = HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        rejected.setBodyConvertFunction(type -> JSON.readValue(body, JSON.constructType(type.getType())));
        return rejected;
    }

    /** payment 가 보내는 DRAW_ENTRY_PAYMENT_SETTLED 봉투. */
    private static String settled(UUID entry, String providerOrderId, String result, String declineReason) {
        String approvedAt = "APPROVED".equals(result) ? "\"2026-10-11T00:00:00Z\"" : "null";
        String reason = declineReason == null ? "null" : "\"" + declineReason + "\"";
        return """
                {"eventId":"%s","eventType":"DRAW_ENTRY_PAYMENT_SETTLED","aggregateType":"DRAW_ENTRY","aggregateId":"%s",
                 "occurredAt":"2026-10-11T00:00:00Z","payload":{"providerOrderId":"%s","result":"%s","amount":300,"approvedAt":%s,"declineReason":%s}}"""
                .formatted(UUID.randomUUID(), entry, providerOrderId, result, approvedAt, reason);
    }

    private String statusOf(UUID entry) {
        return jdbcTemplate.queryForObject("SELECT status FROM draw_entries WHERE id = ?", String.class, (Object) bytes(entry));
    }

    private Map<String, Object> entryRow(UUID entry) {
        return jdbcTemplate.queryForMap("SELECT status, authorizing_provider_order_id FROM draw_entries WHERE id = ?", (Object) bytes(entry));
    }

    private long entriesOf(UUID draw) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM draw_entries WHERE campaign_id = ?", Long.class, (Object) bytes(draw));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
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
}
