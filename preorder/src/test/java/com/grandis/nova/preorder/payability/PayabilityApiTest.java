package com.grandis.nova.preorder.payability;

import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.event.ExternalJobSucceeded;
import com.grandis.nova.preorder.event.PreorderEventHandler;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PreorderIntegrationTest
@AutoConfigureMockMvc
class PayabilityApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderEventHandler handler;

    @Autowired
    CancelStarter cancelStarter;

    @Autowired
    Preorders preorders;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    Long customerId;
    Long preorderId;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(customerId);
        preorderId = accepted.preorder().id();
        token = AcceptFixtures.tokenOf(accepted);
    }

    @Test
    void 결제_가능_기간이면_payable_이고_주문에_필요한_값을_준다() throws Exception {
        makePayable();

        payability(customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preorderId").value(token))
                .andExpect(jsonPath("$.data.preorderInternalId").value(preorderId))
                .andExpect(jsonPath("$.data.customerId").value(customerId))
                .andExpect(jsonPath("$.data.unitPrice").exists())
                .andExpect(jsonPath("$.data.status").value("PAYABLE"))
                .andExpect(jsonPath("$.data.paymentDueAt").exists())
                .andExpect(jsonPath("$.data.payable").value(true))
                .andExpect(jsonPath("$.data.reason").value(nullValue()));
    }

    @Test
    void 외부_등록_전이면_NOT_YET_REGISTERED() throws Exception {
        payability(customerId)
                .andExpect(jsonPath("$.data.payable").value(false))
                .andExpect(jsonPath("$.data.reason").value("NOT_YET_REGISTERED"));
    }

    @Test
    void 기한이_지났는데_만료_처리_전이면_DUE_PASSED() throws Exception {
        makePayable();
        jdbcTemplate.update("UPDATE preorders SET payable_from = UTC_TIMESTAMP(6) - INTERVAL 25 HOUR WHERE id = ?",
                preorderId);

        payability(customerId)
                .andExpect(jsonPath("$.data.payable").value(false))
                .andExpect(jsonPath("$.data.reason").value("DUE_PASSED"));
    }

    @Test
    void 취소_중이면_CANCELING() throws Exception {
        cancelStarter.start(preorders.findById(preorderId).orElseThrow(), EventActor.USER, null, CancelReason.USER);

        payability(customerId)
                .andExpect(jsonPath("$.data.payable").value(false))
                .andExpect(jsonPath("$.data.reason").value("CANCELING"));
    }

    @Test
    void 남의_예약이면_403_관리자는_볼_수_있다() throws Exception {
        payability(fixtures.customer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/internal/preorders/{id}/payability", token).with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void 없는_예약은_404_토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/internal/preorders/{id}/payability", ShopFixtures.unique())
                        .with(customer(customerId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));
        mockMvc.perform(get("/internal/preorders/{id}/payability", token))
                .andExpect(status().isUnauthorized());
    }

    private void makePayable() {
        handler.onExternalJobSucceeded(new ExternalJobSucceeded(fixtures.workerSucceeds(preorderId, "REGISTER"),
                token, "REGISTER", "R-" + ShopFixtures.unique()));
    }

    private ResultActions payability(Long customer) throws Exception {
        return mockMvc.perform(get("/internal/preorders/{id}/payability", token)
                .with(customer(customer)));
    }
}
