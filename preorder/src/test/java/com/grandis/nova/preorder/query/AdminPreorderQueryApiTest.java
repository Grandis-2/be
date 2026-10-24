package com.grandis.nova.preorder.query;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.member.MemberClient;
import com.grandis.nova.preorder.integration.member.MemberNames;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderFact;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.query.application.PreorderDisplayStatus;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderCancels;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PreorderIntegrationTest
@AutoConfigureMockMvc
class AdminPreorderQueryApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    SyncJobs syncJobs;

    @MockitoBean
    CatalogClient catalogClient;

    /**
     * 회원 이름(member 내부 API) 자리. 그 앞의 캐시 · 회로(MemberNames)는 실제 빈이다. 기본은 빈 응답이다 — 정하지 않은 null 응답은
     * MemberNames 안에서 NPE 가 되어 member 회로의 실패로 쌓이고, 시험 순서에 따라 이름 시험이 열린 회로에서 돌게 된다.
     */
    @MockitoBean
    MemberClient memberClient;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    UUID customerId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        customerId = fixtures.customer();
        when(memberClient.getNames(any())).thenReturn(ApiResponse.ok(new MemberClient.Names(List.of())));
        circuitBreakers.circuitBreaker(MemberNames.DEPENDENCY).reset();
    }

    @Test
    void 회원과_상품으로_거르고_오프셋_페이지로_준다() throws Exception {
        AcceptResult mine = accepts.accept(customerId);
        accepts.accept(customerId);
        accepts.accept(fixtures.customer());

        mockMvc.perform(get("/api/v1/admin/preorders").param("customerId", customerId.toString())
                        .param("page", "0").param("size", "1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.customerId").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/preorders").param("productId", mine.preorder().productId().toString())
                        .with(admin()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(mine)))
                .andExpect(jsonPath("$.data.items[0].customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.items[0].registerJobStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].registerSyncJobId")
                        .value(syncJobs.findByPreorder(mine.preorder().id()).getFirst().id().toString()));
    }

    /** DB 를 다른 시험과 함께 쓰므로 이 시험이 만든 상품으로 좁혀 본다. */
    @Test
    void 등록_작업_상태로_재처리_대기_예약을_거른다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        AcceptResult deadLettered = accepts.accept(customerId, product);
        accepts.accept(fixtures.customer(), product);
        jdbcTemplate.update("""
                UPDATE preorder_sync_jobs SET status = 'DEAD_LETTER', dead_lettered_at = UTC_TIMESTAMP(6)
                 WHERE preorder_id = ?
                """, (Object) UuidBinary.toBytes(deadLettered.preorder().id()));

        mockMvc.perform(get("/api/v1/admin/preorders").param("registerJobStatus", "DEAD_LETTER")
                        .param("productId", product.productId().toString()).with(admin()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(deadLettered)))
                .andExpect(jsonPath("$.data.items[0].registerJobStatus").value("DEAD_LETTER"));
    }

    @Test
    void 조건이_하나도_없어도_조회된다() throws Exception {
        // 이 테스트만의 데이터가 아니라 스키마 전체를 본다. 목록이 최신순이므로 방금 만든 예약이 첫 줄이어야 한다.
        AcceptResult accepted = accepts.accept(customerId);

        mockMvc.perform(get("/api/v1/admin/preorders").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(accepted)))
                .andExpect(jsonPath("$.data.items[0].customerId").value(customerId.toString()));
    }

    @Test
    void 기간으로_거른다() throws Exception {
        accepts.accept(customerId);

        mockMvc.perform(get("/api/v1/admin/preorders").param("customerId", customerId.toString())
                        .param("from", Instant.now().plusSeconds(60).toString())
                        .with(admin()))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void 상세는_작업과_시도와_이력을_함께_준다() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);
        UUID jobId = jdbcTemplate.queryForObject("SELECT id FROM preorder_sync_jobs WHERE preorder_id = ?",
                (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)),
                        (Object) UuidBinary.toBytes(accepted.preorder().id()));
        fixtures.syncAttempt(jobId, 1, "TRANSIENT_FAILURE", 503, "UPSTREAM_UNAVAILABLE");
        fixtures.syncAttempt(jobId, 2, null, null, null);

        mockMvc.perform(get("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.admissionTicketId").exists())
                .andExpect(jsonPath("$.data.syncJobs", hasSize(1)))
                .andExpect(jsonPath("$.data.syncJobs[0].jobType").value("REGISTER"))
                .andExpect(jsonPath("$.data.syncJobs[0].attemptCount").value(2))
                .andExpect(jsonPath("$.data.syncJobs[0].lastErrorCode").doesNotExist())
                .andExpect(jsonPath("$.data.syncJobs[0].attempts[0].httpStatus").value(503))
                .andExpect(jsonPath("$.data.syncJobs[0].attempts[1].result").doesNotExist())
                .andExpect(jsonPath("$.data.events", hasSize(1)))
                .andExpect(jsonPath("$.data.cancelable").value(true));
    }

    @Test
    void 내부_메모를_바꾸고_이력은_남기지_않는다() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);

        mockMvc.perform(patch("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"internalNote\":\"VIP 고객\"}")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.internalNote").value("VIP 고객"))
                .andExpect(jsonPath("$.data.events", hasSize(1)));
        assertThat(jdbcTemplate.queryForObject("SELECT internal_note FROM preorders WHERE preorder_token = ?",
                String.class, AcceptFixtures.tokenOf(accepted))).isEqualTo("VIP 고객");
    }

    @Test
    void 메모_외의_필드를_바꾸려_하면_409() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);

        mockMvc.perform(patch("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"internalNote\":\"메모\",\"optionId\":9}")
                        .with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_FIELD_IMMUTABLE"))
                .andExpect(jsonPath("$.error.details.fields[0]").value("optionId"));
    }

    @Test
    void 없는_예약은_404_사용자_토큰은_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/preorders/" + ShopFixtures.unique())
                        .with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/preorders").with(customer(customerId)))
                .andExpect(status().isForbidden());
    }


    @Test
    void 외부_예약_번호로_한_건을_찾고_빈_값은_조건으로_보지_않는다() throws Exception {
        AcceptResult registered = accepts.accept(customerId);
        accepts.accept(customerId);
        String reference = "EXT-" + ShopFixtures.unique();
        fire(registered.preorder().id(), new PreorderFact.RegisterConfirmed(reference));

        mockMvc.perform(get("/api/v1/admin/preorders").param("externalReference", " " + reference + " ")
                        .with(admin()))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(registered)));
        mockMvc.perform(get("/api/v1/admin/preorders").param("externalReference", " ")
                        .param("customerId", customerId.toString()).with(admin()))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    /** 첫 차수를 순번 1 까지로 두어 둘째 접수가 다음 차수로 넘어가게 한다. */
    @Test
    void 상품_안에서_옵션과_배송_차수로_거른다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct(1);
        UUID otherOption = fixtures.option(product.productId(), "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, product.productId(),
                CatalogStubs.activeOption(product.optionId()), CatalogStubs.activeOption(otherOption));
        AcceptResult first = accepts.submit(customerId, product);
        AcceptResult second = accepts.submit(fixtures.customer(), new PreorderProduct(product.productId(),
                otherOption, product.firstBatchId(), product.lastBatchId()));
        String productId = product.productId().toString();

        mockMvc.perform(get("/api/v1/admin/preorders").param("productId", productId)
                        .param("optionId", otherOption.toString()).with(admin()))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(second)));
        mockMvc.perform(get("/api/v1/admin/preorders").param("productId", productId).param("batchNumber", "1")
                        .with(admin()))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(first)));
        mockMvc.perform(get("/api/v1/admin/preorders").param("productId", productId).param("batchNumber", "2")
                        .with(admin()))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(second)))
                .andExpect(jsonPath("$.data.items[0].shipmentBatch.batchNumber").value(2));
        mockMvc.perform(get("/api/v1/admin/preorders").param("productId", productId).param("batchNumber", "9")
                        .with(admin()))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /** 기본은 최신순이고, 결제 기한순은 결제를 기다리는 예약을 기한 임박순으로 앞에 두고 나머지를 뒤에 둔다. */
    @Test
    void 정한_순서로_준다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        AcceptResult first = accepts.accept(fixtures.customer(), product);
        AcceptResult second = accepts.accept(fixtures.customer(), product);
        AcceptResult third = accepts.accept(fixtures.customer(), product);
        // 결제 가능 시작이 접수 순서와 반대가 되게 셋째를 먼저 등록한다
        register(third.preorder().id());
        register(first.preorder().id());
        String productId = product.productId().toString();

        assertOrder(productId, null, third, second, first);
        assertOrder(productId, "NEWEST", third, second, first);
        assertOrder(productId, "OLDEST", first, second, third);
        assertOrder(productId, "QUEUE_POSITION", first, second, third);
        assertOrder(productId, "PAYMENT_DUE", third, first, second);
    }

    @Test
    void 모르는_순서나_상품_없는_순번순은_400_이다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/preorders").param("sort", "PRICE").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/preorders").param("sort", "QUEUE_POSITION").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("sort"));
    }

    @Test
    void 옵션과_배송_차수는_상품_없이_물으면_400_이다() throws Exception {
        mockMvc.perform(get("/api/v1/admin/preorders").param("optionId", UUID.randomUUID().toString())
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("optionId"));
        mockMvc.perform(get("/api/v1/admin/preorders").param("batchNumber", "1").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("batchNumber"));
    }

    /** 거르는 조건(SQL)과 응답의 화면 단계 계산이 어긋나면 여기서 드러난다. 단계마다 예약을 하나씩 둔다. */
    @Test
    void 화면_단계로_거르면_그_단계의_예약만_나오고_응답의_화면_단계와_같다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        Map<PreorderDisplayStatus, String> tokens = new EnumMap<>(PreorderDisplayStatus.class);
        for (PreorderDisplayStatus stage : PreorderDisplayStatus.values()) {
            AcceptResult accepted = accepts.accept(fixtures.customer(), product);
            tokens.put(stage, AcceptFixtures.tokenOf(accepted));
            advance(accepted.preorder().id(), stage);
        }

        for (PreorderDisplayStatus stage : PreorderDisplayStatus.values()) {
            mockMvc.perform(get("/api/v1/admin/preorders").param("productId", product.productId().toString())
                            .param("displayStatus", stage.name()).with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.items[0].preorderId").value(tokens.get(stage)))
                    .andExpect(jsonPath("$.data.items[0].displayStatus").value(stage.name()));
        }
    }

    @Test
    void 상태와_화면_단계가_맞지_않으면_아무것도_나오지_않는다() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);
        String productId = accepted.preorder().productId().toString();

        mockMvc.perform(get("/api/v1/admin/preorders").param("productId", productId)
                        .param("status", "CANCELED").param("displayStatus", "RECEIVED").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/admin/preorders").param("productId", productId)
                        .param("status", "PENDING_SYNC").param("displayStatus", "RECEIVED").with(admin()))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    private void assertOrder(String productId, String sort, AcceptResult... expected) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/admin/preorders").param("productId", productId)
                .with(admin());
        if (sort != null) {
            request.param("sort", sort);
        }
        ResultActions result = mockMvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(expected.length)));
        for (int i = 0; i < expected.length; i++) {
            result.andExpect(jsonPath("$.data.items[" + i + "].preorderId").value(AcceptFixtures.tokenOf(expected[i])));
        }
    }

    /** 접수 직후(RECEIVED)의 예약을 그 화면 단계까지 보낸다. */
    private void advance(UUID preorderId, PreorderDisplayStatus stage) {
        switch (stage) {
            case RECEIVED -> { }
            // 포기해 재처리를 기다리는 작업도 처리 중으로 보인다
            case PROCESSING -> fixtures.deadLetter(preorderId);
            case PAYABLE -> register(preorderId);
            case PAYMENT_IN_PROGRESS -> {
                register(preorderId);
                fire(preorderId, new PreorderFact.PaymentStarted(Instant.now()));
            }
            case PAYMENT_EXPIRED -> {
                register(preorderId);
                fixtures.passPaymentDue(preorderId);
            }
            case RESERVED -> {
                register(preorderId);
                fire(preorderId, new PreorderFact.PaymentConfirmed(Instant.now()));
            }
            case CANCELING -> fire(preorderId, new PreorderFact.CancelRequested(EventActor.USER, null));
            case CANCELED -> new PreorderCancels(ledger, transactionTemplate).complete(preorderId);
        }
    }

    private void register(UUID preorderId) {
        fire(preorderId, new PreorderFact.RegisterConfirmed("EXT-" + ShopFixtures.unique()));
    }

    private void fire(UUID preorderId, PreorderFact fact) {
        transactionTemplate.executeWithoutResult(status -> ledger.fire(preorderId, fact));
    }

    @Test
    void 목록은_그_쪽의_회원_이름을_member_에게_한_번에_묻는다() throws Exception {
        // 같은 회원이 같은 상품에 다시 접수하려면 대기열을 다시 거쳐야 해 상품을 나눈다. 목록이 최신순이라 맨 앞 세 줄이 이 시험의 예약이다
        UUID kim = fixtures.customer();
        UUID lee = fixtures.customer();
        accepts.accept(kim);
        accepts.accept(lee);
        accepts.accept(kim);
        when(memberClient.getNames(any())).thenAnswer(invocation -> names(invocation.getArgument(0), kim, "김**", lee, "이**"));

        mockMvc.perform(get("/api/v1/admin/preorders").param("size", "3").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].customerName").value("김**"))
                .andExpect(jsonPath("$.data.items[1].customerName").value("이**"))
                .andExpect(jsonPath("$.data.items[2].customerName").value("김**"));
        verify(memberClient, times(1)).getNames(argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(kim, lee))));
    }

    @Test
    void 상세와_메모_변경도_회원_이름을_싣고_캐시에_있으면_다시_묻지_않는다() throws Exception {
        UUID park = fixtures.customer();
        AcceptResult accepted = accepts.accept(park);
        when(memberClient.getNames(any())).thenAnswer(invocation -> names(invocation.getArgument(0), park, "박**"));

        mockMvc.perform(get("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("박**"));
        clearInvocations(memberClient);
        mockMvc.perform(patch("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"internalNote\":\"이름 확인\"}").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("박**"));
        verify(memberClient, times(0)).getNames(any());
    }

    @Test
    void member_가_답하지_않으면_목록과_상세는_그대로이고_이름만_비어_있다() throws Exception {
        UUID choi = fixtures.customer();
        AcceptResult accepted = accepts.accept(choi);
        when(memberClient.getNames(any())).thenThrow(new ResourceAccessException("member 연결 실패"));

        mockMvc.perform(get("/api/v1/admin/preorders").param("customerId", choi.toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].customerId").value(choi.toString()))
                .andExpect(jsonPath("$.data.items[0].customerName").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").doesNotExist());
    }

    /** 물은 id 중 아는 회원만 담은 member 응답. */
    private static ApiResponse<MemberClient.Names> names(Collection<UUID> asked, Object... idAndNames) {
        List<MemberClient.Name> items = new java.util.ArrayList<>();
        for (int i = 0; i < idAndNames.length; i += 2) {
            UUID id = (UUID) idAndNames[i];
            if (asked.contains(id)) {
                items.add(new MemberClient.Name(id, (String) idAndNames[i + 1]));
            }
        }
        return ApiResponse.ok(new MemberClient.Names(items));
    }
}
