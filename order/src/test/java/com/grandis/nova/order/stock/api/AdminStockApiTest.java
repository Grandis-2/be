package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.domain.model.StockLevel;
import com.grandis.nova.order.stock.domain.repository.StockReader;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 재고 설정(PUT) · 초기화(POST). 요청마다 커밋하므로 "아무것도 안 바뀜" 을 DB 에서 본다. */
@OrderIntegrationTest
@AutoConfigureMockMvc
class AdminStockApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StockReader reader;

    OrderFixtures fixtures;
    StockProduct product;
    Long first;
    Long second;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.inStockProduct(2);
        first = product.optionIds().get(0);
        second = product.optionIds().get(1);
    }

    @Test
    void putSetsExistingAndCreatesMissingInOptionOrder() throws Exception {
        fixtures.stock(first, 10, 3, 2);

        put(product.productId(), """
                {"items":[{"optionId":%d,"stockTotal":7},{"optionId":%d,"stockTotal":6}]}
                """.formatted(second, first), TestAuth.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(product.productId()))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].optionId").value(first))
                .andExpect(jsonPath("$.data.items[0].stockTotal").value(6))
                .andExpect(jsonPath("$.data.items[0].stockReserved").value(3))
                .andExpect(jsonPath("$.data.items[0].stockSold").value(2))
                .andExpect(jsonPath("$.data.items[0].available").value(1))
                .andExpect(jsonPath("$.data.items[0].created").value(false))
                .andExpect(jsonPath("$.data.items[1].optionId").value(second))
                .andExpect(jsonPath("$.data.items[1].stockTotal").value(7))
                .andExpect(jsonPath("$.data.items[1].available").value(7))
                .andExpect(jsonPath("$.data.items[1].created").value(true));
    }

    @Test
    void belowCommittedIs409AndLeavesWholeRequestUnapplied() throws Exception {
        fixtures.stock(first, 10, 3, 2);

        put(product.productId(), """
                {"items":[{"optionId":%d,"stockTotal":5},{"optionId":%d,"stockTotal":4}]}
                """.formatted(second, first), TestAuth.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOCK_BELOW_COMMITTED"))
                .andExpect(jsonPath("$.error.details.options.length()").value(1))
                .andExpect(jsonPath("$.error.details.options[0].optionId").value(first))
                .andExpect(jsonPath("$.error.details.options[0].committed").value(5));

        assertThat(reader.findByOptionIds(List.of(first, second))).containsExactly(new StockLevel(first, 10, 3, 2));
    }

    /**
     * 등록 재개 시나리오: 첫 재고 호출이 응답 없이 커밋됐고, 그사이 관리자가 고쳤고, 등록이 같은 초기값으로 다시 부른다.
     * 초기화는 관리자 값을 덮지 않고, 처음 보는 옵션만 만든다.
     */
    @Test
    void postDoesNotOverwriteAdminChangesMadeBeforeRegistrationResumes() throws Exception {
        post(product.productId(), body(first, 5)).andExpect(status().isOk());
        put(product.productId(), body(first, 10), TestAuth.admin()).andExpect(status().isOk());

        post(product.productId(), """
                {"items":[{"optionId":%d,"stockTotal":5},{"optionId":%d,"stockTotal":3}]}
                """.formatted(first, second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].optionId").value(first))
                .andExpect(jsonPath("$.data.items[0].stockTotal").value(10))
                .andExpect(jsonPath("$.data.items[0].created").value(false))
                .andExpect(jsonPath("$.data.items[1].optionId").value(second))
                .andExpect(jsonPath("$.data.items[1].stockTotal").value(3))
                .andExpect(jsonPath("$.data.items[1].created").value(true));

        assertThat(reader.findByOptionIds(List.of(first, second)))
                .containsExactly(new StockLevel(first, 10, 0, 0), new StockLevel(second, 3, 0, 0));
    }

    @Test
    void postChecksProductLikePut() throws Exception {
        OrderFixtures.PreorderProduct preorder = fixtures.preorderProduct();
        Long foreign = fixtures.inStockProduct(1).optionIds().get(0);

        post(preorder.productId(), body(preorder.optionId(), 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOCK_NOT_TRACKED"));
        post(product.productId(), body(foreign, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("items[0].optionId"));
        post(Long.MAX_VALUE, body(first, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/products/{productId}/stock", product.productId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}").with(TestAuth.admin()))
                .andExpect(status().isBadRequest());

        assertThat(reader.findByOptionIds(List.of(preorder.optionId(), foreign, first))).isEmpty();
    }

    @Test
    void unknownProductIs404() throws Exception {
        put(Long.MAX_VALUE, body(first, 1), TestAuth.admin())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void preorderProductIs409() throws Exception {
        OrderFixtures.PreorderProduct preorder = fixtures.preorderProduct();

        put(preorder.productId(), body(preorder.optionId(), 1), TestAuth.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOCK_NOT_TRACKED"));

        assertThat(reader.findByOptionIds(List.of(preorder.optionId()))).isEmpty();
    }

    @Test
    void optionOfAnotherProductIs400AtItsPosition() throws Exception {
        Long foreign = fixtures.inStockProduct(1).optionIds().get(0);

        put(product.productId(), """
                {"items":[{"optionId":%d,"stockTotal":1},{"optionId":%d,"stockTotal":1}]}
                """.formatted(first, foreign), TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations.length()").value(1))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("items[1].optionId"));

        assertThat(reader.findByOptionIds(List.of(first, foreign))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"items\":[]}",
            "{\"items\":[null]}",
            "{\"items\":[{\"stockTotal\":1}]}",
            "{\"items\":[{\"optionId\":0,\"stockTotal\":1}]}",
            "{\"items\":[{\"optionId\":1,\"stockTotal\":-1}]}",
            "{\"items\":[{\"optionId\":1}]}",
            "{\"items\":[{\"optionId\":1,\"stockTotal\":1},{\"optionId\":1,\"stockTotal\":2}]}"
    })
    void malformedRequestIs400(String body) throws Exception {
        put(product.productId(), body, TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // 소수 · int 를 넘는 값은 조용히 잘리거나 넘치지 않고 거절된다. 잘리면 다른 옵션 · 다른 수량이 들어간다.
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"items\":[{\"optionId\":%d,\"stockTotal\":10.7}]}",
            "{\"items\":[{\"optionId\":%d.5,\"stockTotal\":1}]}",
            "{\"items\":[{\"optionId\":%d,\"stockTotal\":2147483648}]}"
    })
    void lossyNumbersAre400(String template) throws Exception {
        put(product.productId(), template.formatted(first), TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(reader.findByOptionIds(List.of(first))).isEmpty();
    }

    // 정수 칸은 JSON 정수 토큰만 받는다. 숫자 문자열 · 불리언 · 소수 표기 · 지수 표기 · null 은 모두 400 이다.
    @ParameterizedTest
    @ValueSource(strings = {"\"5\"", "true", "5.0", "1e3", "null"})
    void nonIntegerStockTotalIs400(String token) throws Exception {
        put(product.productId(), "{\"items\":[{\"optionId\":%d,\"stockTotal\":%s}]}".formatted(first, token),
                TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(reader.findByOptionIds(List.of(first))).isEmpty();
    }

    /** %d 자리에 실제 옵션 id 를 넣어, 정수로 읽히면 그 옵션에 들어갈 값으로 시험한다. */
    @ParameterizedTest
    @ValueSource(strings = {"\"%d\"", "true", "%d.0", "%de0", "null"})
    void nonIntegerOptionIdIs400(String template) throws Exception {
        put(product.productId(), "{\"items\":[{\"optionId\":%s,\"stockTotal\":1}]}".formatted(
                template.formatted(first)), TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(reader.findByOptionIds(List.of(first))).isEmpty();
    }

    @Test
    void moreThanMaxItemsIs400() throws Exception {
        String items = String.join(",", LongStream.rangeClosed(1, StockRequest.MAX_ITEMS + 1)
                .mapToObj(id -> "{\"optionId\":%d,\"stockTotal\":1}".formatted(id)).toList());

        put(product.productId(), "{\"items\":[" + items + "]}", TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nonNumericProductIdIs400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/admin/products/abc/stock")
                        .contentType(MediaType.APPLICATION_JSON).content(body(first, 1)).with(TestAuth.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void customerIsForbidden() throws Exception {
        put(product.productId(), body(first, 1), TestAuth.customer(1L))
                .andExpect(status().isForbidden());

        assertThat(reader.findByOptionIds(List.of(first))).isEmpty();
    }

    private ResultActions put(Long productId, String body, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/admin/products/{productId}/stock", productId)
                .contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private ResultActions post(Long productId, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/products/{productId}/stock", productId)
                .contentType(MediaType.APPLICATION_JSON).content(body).with(TestAuth.admin()));
    }

    private static String body(Long optionId, int total) {
        return "{\"items\":[{\"optionId\":%d,\"stockTotal\":%d}]}".formatted(optionId, total);
    }
}
