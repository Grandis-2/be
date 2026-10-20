package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.support.GeneralLog;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.StockProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 서비스 간 재고 조회(contracts/order-internal.md "GET /internal/stocks"). 토큰 없이 부른다 — catalog 의 비회원 목록과 같다. */
@OrderIntegrationTest
@AutoConfigureMockMvc
class InternalStockApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    OrderFixtures fixtures;
    UUID first;
    UUID second;
    UUID missing;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        StockProduct product = fixtures.inStockProduct(3);
        first = product.optionIds().get(0);
        second = product.optionIds().get(1);
        missing = product.optionIds().get(2);
        fixtures.stock(first, 10, 3, 2);
        fixtures.stock(second, 4, 0, 0);
    }

    /** 요청 순서 그대로, 같은 id 는 처음 자리 한 번. 재고 행이 없는 옵션은 빠진다. 토큰 없이 200 이다. */
    @Test
    void returnsAvailableInRequestOrderWithoutTokenSkippingMissingRows() throws Exception {
        stocks("%s,%s,%s,%s".formatted(second, missing, first, second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].optionId").value(second.toString()))
                .andExpect(jsonPath("$.data.items[0].available").value(4))
                .andExpect(jsonPath("$.data.items[1].optionId").value(first.toString()))
                .andExpect(jsonPath("$.data.items[1].available").value(5));
    }

    @Test
    void repeatedParameterIsTheSameAsCommaSeparated() throws Exception {
        mockMvc.perform(get("/internal/stocks").param("optionIds", first.toString()).param("optionIds", second.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].optionId").value(first.toString()))
                .andExpect(jsonPath("$.data.items[1].optionId").value(second.toString()));
    }

    /** 같은 id 를 빼고 100개까지 받는다(중복은 상한에 세지 않는다). */
    @Test
    void acceptsHundredDistinctIdsPlusDuplicates() throws Exception {
        stocks(ids(99) + "," + first + "," + first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void moreThanHundredDistinctIdsIs400() throws Exception {
        stocks(ids(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("optionIds"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid", "{first},", "{first},,{first}"})
    void emptyMalformedOrBlankElementIs400(String optionIds) throws Exception {
        stocks(optionIds.replace("{first}", first.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("optionIds"));
    }

    @Test
    void missingParameterIs400() throws Exception {
        mockMvc.perform(get("/internal/stocks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("optionIds"));
    }

    /**
     * 토큰을 실어 와도 같다 — catalog 의 토큰 중계(BearerTokenRelayInterceptor)가 회원 토큰을 붙여도 된다.
     * 만료 · 위조 토큰도 공개 경로에서는 401 이 아니라 익명으로 통과한다(JwtAuthenticationFilter).
     */
    @Test
    void forwardedOrBrokenTokenIsAlsoAccepted() throws Exception {
        mockMvc.perform(get("/internal/stocks").param("optionIds", first.toString()).with(TestAuth.customer(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].available").value(5));
        mockMvc.perform(get("/internal/stocks").param("optionIds", first.toString()).header("Authorization", "Bearer broken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].available").value(5));
    }

    /** 트랜잭션을 열지 않는다 — 서버에 보내는 문장이 재고 SELECT 하나이고 준비 · 정리 문장이 없다(StockQueryService). */
    @Test
    void readsWithOneStatementWithoutTransaction() throws Exception {
        List<String> statements = GeneralLog.statements("%FROM option_inventories%WHERE option_id IN%",
                () -> stocks("%s,%s".formatted(first, second)).andExpect(status().isOk()));

        assertThat(statements).as("재고 SELECT — 양성 대조군").anyMatch(sql -> sql.contains("FROM option_inventories"));
        assertThat(statements).as("준비 · 정리 문장").noneMatch(GeneralLog::isTransactionHousekeeping);
        assertThat(statements).as("문장 하나").singleElement();
    }

    private ResultActions stocks(String optionIds) throws Exception {
        return mockMvc.perform(get("/internal/stocks").param("optionIds", optionIds));
    }

    /** 재고 행이 없는 새 id n개(first 와 겹치지 않음). */
    private static String ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
    }
}
