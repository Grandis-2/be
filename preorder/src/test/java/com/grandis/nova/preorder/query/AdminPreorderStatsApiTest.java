package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.deadletter.DeadLetters;
import com.grandis.nova.preorder.deadletter.IncomingDeadLetter;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.PreorderFact;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DB 를 다른 시험과 함께 쓰므로 정확한 수는 이 시험이 만든 상품으로 좁혀 본다.
 * 결과를 잠깐 캐시하므로 시험마다 새 상품을 쓰고, 데이터를 다 만든 뒤에 한 번만 묻는다.
 */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class AdminPreorderStatsApiTest {

    static final String PATH = "/api/v1/admin/preorders/stats";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderLedger ledger;

    @Autowired
    DeadLetters deadLetters;

    @Autowired
    TransactionTemplate transactionTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
    }

    @Test
    void 상품_하나의_예약_상태와_끝나지_않은_작업과_DLQ_대기와_결제_기한_초과를_센다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        AcceptResult waiting = accepts.accept(fixtures.customer(), product);
        fixtures.deadLetter(accepts.accept(fixtures.customer(), product).preorder().id());
        register(accepts.accept(fixtures.customer(), product).preorder().id());
        UUID overdue = accepts.accept(fixtures.customer(), product).preorder().id();
        register(overdue);
        fixtures.passPaymentDue(overdue);
        deadLetters.record(new IncomingDeadLetter("preorder-events", ShopFixtures.unique(),
                event(AcceptFixtures.tokenOf(waiting)), 5, null, null));
        // 다른 상품의 예약은 세지 않는다
        fixtures.deadLetter(accepts.accept(fixtures.customer()).preorder().id());

        mockMvc.perform(get(PATH).param("productId", product.productId().toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(product.productId().toString()))
                .andExpect(jsonPath("$.data.preorders.PENDING_SYNC").value(2))
                .andExpect(jsonPath("$.data.preorders.REGISTERED").value(2))
                .andExpect(jsonPath("$.data.preorders.RESERVED").value(0))
                .andExpect(jsonPath("$.data.preorders.CANCELING").value(0))
                .andExpect(jsonPath("$.data.preorders.CANCELED").value(0))
                .andExpect(jsonPath("$.data.syncJobs.REGISTER.PENDING").value(1))
                .andExpect(jsonPath("$.data.syncJobs.REGISTER.PROCESSING").value(0))
                .andExpect(jsonPath("$.data.syncJobs.REGISTER.RETRY_SCHEDULED").value(0))
                .andExpect(jsonPath("$.data.syncJobs.REGISTER.DEAD_LETTER").value(1))
                .andExpect(jsonPath("$.data.syncJobs.REGISTER.SUCCEEDED").doesNotExist())
                .andExpect(jsonPath("$.data.syncJobs.CANCEL.PENDING").value(0))
                .andExpect(jsonPath("$.data.deadLetters.waiting").value(1))
                .andExpect(jsonPath("$.data.deadLetters.oldestWaitingAt").isNotEmpty())
                .andExpect(jsonPath("$.data.paymentOverdue").value(1))
                .andExpect(jsonPath("$.data.oldestPendingSyncAt")
                        .value(waiting.preorder().createdAt().toString()))
                .andExpect(jsonPath("$.data.countedAt").isNotEmpty());
    }

    /** 재처리를 기다리는(DEAD_LETTER) 예약은 등록을 기다리는 시각에는 들고, 아직 도는 등록의 시각에는 들지 않는다. */
    @Test
    void 처리_중인_등록의_가장_오래된_시각은_재처리_대기를_빼고_센다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        AcceptResult deadLettered = accepts.accept(fixtures.customer(), product);
        fixtures.deadLetter(deadLettered.preorder().id());
        AcceptResult inFlight = accepts.accept(fixtures.customer(), product);
        register(accepts.accept(fixtures.customer(), product).preorder().id());

        mockMvc.perform(get(PATH).param("productId", product.productId().toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.oldestPendingSyncAt")
                        .value(deadLettered.preorder().createdAt().toString()))
                .andExpect(jsonPath("$.data.oldestInFlightSyncAt")
                        .value(inFlight.preorder().createdAt().toString()));
    }

    @Test
    void 예약이_없는_상품은_모두_0_이고_가장_오래된_시각은_비어_있다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();

        mockMvc.perform(get(PATH).param("productId", product.productId().toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preorders.PENDING_SYNC").value(0))
                .andExpect(jsonPath("$.data.syncJobs.REGISTER.PENDING").value(0))
                .andExpect(jsonPath("$.data.deadLetters.waiting").value(0))
                .andExpect(jsonPath("$.data.deadLetters.oldestWaitingAt").value(nullValue()))
                .andExpect(jsonPath("$.data.paymentOverdue").value(0))
                .andExpect(jsonPath("$.data.oldestPendingSyncAt").value(nullValue()))
                .andExpect(jsonPath("$.data.oldestInFlightSyncAt").value(nullValue()));
    }

    @Test
    void 상품을_주지_않으면_전체를_센다() throws Exception {
        accepts.accept(fixtures.customer());

        mockMvc.perform(get(PATH).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(nullValue()))
                .andExpect(jsonPath("$.data.preorders.PENDING_SYNC", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.syncJobs.REGISTER.PENDING", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.oldestPendingSyncAt").isNotEmpty());
    }

    @Test
    void 사전예약_상품이_아니면_404_다() throws Exception {
        mockMvc.perform(get(PATH).param("productId", UUID.randomUUID().toString()).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void 관리자만_볼_수_있다() throws Exception {
        mockMvc.perform(get(PATH).with(customer(fixtures.customer()))).andExpect(status().isForbidden());
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    /** 외부 등록이 확인돼 결제를 기다리는 예약으로 만든다. 작업도 성공으로 둔다. */
    private void register(UUID preorderId) {
        transactionTemplate.executeWithoutResult(status ->
                ledger.fire(preorderId, new PreorderFact.RegisterConfirmed("EXT-" + ShopFixtures.unique())));
        fixtures.workerSucceeds(preorderId, "REGISTER");
    }

    private String event(String preorderToken) {
        return """
                {"eventId":"%s","eventType":"EXTERNAL_JOB_SUCCEEDED","aggregateType":"PREORDER",
                 "aggregateId":"00000000-0000-7000-8000-000000000001",
                 "occurredAt":"2026-09-03T01:00:03.470Z","payload":{"preorderId":"%s"}}
                """.formatted(ShopFixtures.unique(), preorderToken);
    }
}
