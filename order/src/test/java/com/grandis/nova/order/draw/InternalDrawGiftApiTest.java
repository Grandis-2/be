package com.grandis.nova.order.draw;

import com.grandis.nova.order.draw.domain.model.NewDrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 내부 API — 상품이 반영(settled_at) 전 회차의 증정품인가. catalog 의 상품 삭제가 관리자 토큰으로 묻는다. */
@OrderIntegrationTest
@AutoConfigureMockMvc
@DisplayName("드로우 증정품 내부 조회")
class InternalDrawGiftApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DrawCampaignStore campaigns;

    @Test
    @DisplayName("반영 전 회차가 하나라도 있으면 true — 예정 · 응모 중 · 마감 뒤 반영 전. 전부 반영되면 false, 회차가 없는 상품도 false")
    void inProgressUntilEverySettled() throws Exception {
        OrderFixtures.StockProduct gift = new OrderFixtures(jdbcTemplate).inStockProduct(1);
        UUID productId = gift.productId();
        ask(productId).andExpect(status().isOk()).andExpect(jsonPath("$.data.drawInProgress").value(false))
                .andExpect(jsonPath("$.data.productId").value(productId.toString()));

        Instant now = Instant.now();
        UUID closed = create(gift, now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(1)));
        UUID scheduled = create(gift, now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)));
        ask(productId).andExpect(jsonPath("$.data.drawInProgress").value(true));

        settle(closed);
        ask(productId).andExpect(jsonPath("$.data.drawInProgress").value(true));
        settle(scheduled);
        ask(productId).andExpect(jsonPath("$.data.drawInProgress").value(false));
        ask(UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.data.drawInProgress").value(false));
    }

    @Test
    @DisplayName("회원 토큰은 403 — 상품 삭제는 관리자만 한다")
    void memberIsForbidden() throws Exception {
        mockMvc.perform(get("/internal/draws/gift-products/{id}", UUID.randomUUID()).with(TestAuth.customer(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    private ResultActions ask(UUID productId) throws Exception {
        return mockMvc.perform(get("/internal/draws/gift-products/{id}", productId).with(TestAuth.admin()));
    }

    private UUID create(OrderFixtures.StockProduct gift, Instant opensAt, Instant closesAt) {
        return new TransactionTemplate(transactionManager).execute(status -> campaigns.insert(new NewDrawCampaign(UUID.randomUUID().toString(),
                gift.productId(), gift.optionIds().getFirst(), "증정품 시험", "증정품", null, "기본", null, new BigDecimal("100"), 1, opensAt, closesAt)).id());
    }

    private void settle(UUID campaignId) {
        jdbcTemplate.update("UPDATE draw_campaigns SET settled_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) OrderFixtures.bytes(campaignId));
    }
}
