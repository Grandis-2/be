package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 진행 중인 예약이 한 페이지를 넘을 때. 넘친 예약을 잘라 버리면 그 상품이 예약하지 않은 것으로 보이므로
 * 다음 커서로 끝까지 이어 읽을 수 있어야 한다. 페이지 크기를 2 로 줄여 본다.
 */
@PreorderIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.preorder-query.active-page-size=2")
class ActivePreorderPagingApiTest {

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
    UUID customerId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        cancels = new PreorderCancels(ledger, transactionTemplate);
        customerId = fixtures.customer();
    }

    @Test
    void 한_페이지를_넘으면_다음_커서로_나머지를_빠짐없이_이어_읽는다() throws Exception {
        AcceptResult oldest = accepts.accept(customerId);
        cancels.complete(accepts.accept(customerId).preorder().id());
        AcceptResult middle = accepts.accept(customerId);
        AcceptResult newest = accepts.accept(customerId);

        String first = mockMvc.perform(get("/api/v1/preorders/active").with(customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(newest)))
                .andExpect(jsonPath("$.data.items[1].preorderId").value(AcceptFixtures.tokenOf(middle)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String cursor = JsonPath.read(first, "$.data.nextCursor");

        mockMvc.perform(get("/api/v1/preorders/active").param("cursor", cursor).with(customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].preorderId").value(AcceptFixtures.tokenOf(oldest)))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void 꼭_한_페이지만큼이면_다음_커서가_없다() throws Exception {
        accepts.accept(customerId);
        accepts.accept(customerId);

        mockMvc.perform(get("/api/v1/preorders/active").with(customer(customerId)))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }
}
