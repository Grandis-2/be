package com.grandis.nova.preorder.api;

import com.grandis.nova.preorder.accept.AcceptResult;
import com.grandis.nova.preorder.accept.PreorderAcceptService;
import com.grandis.nova.preorder.catalog.CatalogClient;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderCancels;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PreorderIntegrationTest
@AutoConfigureMockMvc
class PreorderQueryApiTest {

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

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    AcceptFixtures accepts;
    PreorderCancels cancels;
    Long customerId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        cancels = new PreorderCancels(ledger, transactionTemplate);
        customerId = fixtures.customer();
    }

    @Test
    void 내_예약만_최신순으로_돌려준다() throws Exception {
        AcceptResult older = accepts.accept(customerId);
        AcceptResult newer = accepts.accept(customerId);
        accepts.accept(fixtures.customer());

        mockMvc.perform(get("/api/v1/preorders").with(user(customerId.toString()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(newer)))
                .andExpect(jsonPath("$.data.items[1].preorderId").value(AcceptFixtures.tokenOf(older)))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void 커서로_다음_페이지를_이어_읽는다() throws Exception {
        List<AcceptResult> accepted = List.of(accepts.accept(customerId), accepts.accept(customerId), accepts.accept(customerId));

        String firstPage = mockMvc.perform(get("/api/v1/preorders").param("size", "2")
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(accepted.get(2))))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(firstPage, "$.data.nextCursor");

        mockMvc.perform(get("/api/v1/preorders").param("size", "2").param("cursor", cursor)
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(accepted.getFirst())))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void 상태와_상품으로_거른다() throws Exception {
        AcceptResult kept = accepts.accept(customerId);
        AcceptResult canceled = accepts.accept(customerId);
        cancels.complete(canceled.preorder().getId());

        mockMvc.perform(get("/api/v1/preorders").param("status", "PENDING_SYNC")
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(kept)));
        mockMvc.perform(get("/api/v1/preorders").param("productId", kept.preorder().getProductId().toString())
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(kept)));
    }

    @Test
    void 잘못된_크기나_커서는_400() throws Exception {
        mockMvc.perform(get("/api/v1/preorders").param("size", "0")
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("size"));
        for (String brokenCursor : List.of("!!not-base64!!",
                Base64.getUrlEncoder().withoutPadding().encodeToString("어제|3".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding().encodeToString("2026-10-01T00:00:00Z".getBytes(StandardCharsets.UTF_8)))) {
            mockMvc.perform(get("/api/v1/preorders").param("cursor", brokenCursor)
                            .with(user(customerId.toString()).roles("USER")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void 상세는_결제_기한과_취소_가능_여부를_준다() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);
        String externalReference = "EXT-" + ShopFixtures.unique();
        transactionTemplate.executeWithoutResult(status ->
                ledger.confirmRegister(accepted.preorder().getId(), externalReference));

        mockMvc.perform(get("/api/v1/preorders/" + AcceptFixtures.tokenOf(accepted))
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYABLE"))
                .andExpect(jsonPath("$.data.externalReference").value(externalReference))
                .andExpect(jsonPath("$.data.cancelable").value(true))
                .andExpect(jsonPath("$.data.paymentDueAt").exists())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.shipmentBatch.batchNumber").value(1));
    }

    @Test
    void 취소된_예약은_결제_기한도_취소_버튼도_없다() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);
        cancels.complete(accepted.preorder().getId());

        mockMvc.perform(get("/api/v1/preorders/" + AcceptFixtures.tokenOf(accepted))
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.paymentDueAt").doesNotExist())
                .andExpect(jsonPath("$.data.cancelable").value(false));
    }

    @Test
    void 남의_예약은_존재를_알리지_않고_404_관리자는_볼_수_있다() throws Exception {
        AcceptResult mine = accepts.accept(customerId);

        mockMvc.perform(get("/api/v1/preorders/" + AcceptFixtures.tokenOf(mine))
                        .with(user(fixtures.customer().toString()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/preorders/" + AcceptFixtures.tokenOf(mine)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preorderId").value(AcceptFixtures.tokenOf(mine)));
    }

    @Test
    void 이력은_번호_순으로_준다() throws Exception {
        AcceptResult accepted = accepts.accept(customerId);
        cancels.complete(accepted.preorder().getId());

        mockMvc.perform(get("/api/v1/preorders/" + AcceptFixtures.tokenOf(accepted) + "/history")
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].eventSequence").value(1))
                .andExpect(jsonPath("$.data.items[0].fromStatus").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].toStatus").value("PENDING_SYNC"))
                .andExpect(jsonPath("$.data.items[2].toStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.items[2].actor").value("SYSTEM"));
    }

    @Test
    void 로그인하지_않으면_401_관리자_토큰은_목록을_못_본다() throws Exception {
        mockMvc.perform(get("/api/v1/preorders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/preorders").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

}
