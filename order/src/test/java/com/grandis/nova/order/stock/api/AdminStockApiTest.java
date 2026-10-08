package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.stock.domain.model.StockLevel;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.StockProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import com.grandis.nova.order.support.TestIds;
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
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 재고 조회(GET) · 설정(PUT) · 초기화(POST). 요청마다 커밋하므로 "아무것도 안 바뀜" 을 DB 에서 본다. */
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
    UUID first;
    UUID second;

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
                {"items":[{"optionId":"%s","stockTotal":7},{"optionId":"%s","stockTotal":6}]}
                """.formatted(second, first), TestAuth.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(product.productId().toString()))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].optionId").value(first.toString()))
                .andExpect(jsonPath("$.data.items[0].stockTotal").value(6))
                .andExpect(jsonPath("$.data.items[0].stockReserved").value(3))
                .andExpect(jsonPath("$.data.items[0].stockSold").value(2))
                .andExpect(jsonPath("$.data.items[0].available").value(1))
                .andExpect(jsonPath("$.data.items[0].created").value(false))
                .andExpect(jsonPath("$.data.items[1].optionId").value(second.toString()))
                .andExpect(jsonPath("$.data.items[1].stockTotal").value(7))
                .andExpect(jsonPath("$.data.items[1].available").value(7))
                .andExpect(jsonPath("$.data.items[1].created").value(true));
    }

    @Test
    void belowCommittedIs409AndLeavesWholeRequestUnapplied() throws Exception {
        fixtures.stock(first, 10, 3, 2);

        put(product.productId(), """
                {"items":[{"optionId":"%s","stockTotal":5},{"optionId":"%s","stockTotal":4}]}
                """.formatted(second, first), TestAuth.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOCK_BELOW_COMMITTED"))
                .andExpect(jsonPath("$.error.details.options.length()").value(1))
                .andExpect(jsonPath("$.error.details.options[0].optionId").value(first.toString()))
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
                {"items":[{"optionId":"%s","stockTotal":5},{"optionId":"%s","stockTotal":3}]}
                """.formatted(first, second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].optionId").value(first.toString()))
                .andExpect(jsonPath("$.data.items[0].stockTotal").value(10))
                .andExpect(jsonPath("$.data.items[0].created").value(false))
                .andExpect(jsonPath("$.data.items[1].optionId").value(second.toString()))
                .andExpect(jsonPath("$.data.items[1].stockTotal").value(3))
                .andExpect(jsonPath("$.data.items[1].created").value(true));

        assertThat(reader.findByOptionIds(List.of(first, second)))
                .containsExactly(new StockLevel(first, 10, 0, 0), new StockLevel(second, 3, 0, 0));
    }

    @Test
    void postChecksProductLikePut() throws Exception {
        OrderFixtures.PreorderProduct preorder = fixtures.preorderProduct();
        UUID foreign = fixtures.inStockProduct(1).optionIds().get(0);

        post(preorder.productId(), body(preorder.optionId(), 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOCK_NOT_TRACKED"));
        post(product.productId(), body(foreign, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("items[0].optionId"));
        post(UUID.randomUUID(), body(first, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/products/{productId}/stock", product.productId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}").with(TestAuth.admin()))
                .andExpect(status().isBadRequest());

        assertThat(reader.findByOptionIds(List.of(preorder.optionId(), foreign, first))).isEmpty();
    }

    // 재고를 넣지 않은 first 도 registered=false 와 0 으로 싣는다.
    @Test
    void getListsEveryOptionInOrderMarkingUnregistered() throws Exception {
        fixtures.stock(second, 8, 1, 2);

        get(product.productId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(product.productId().toString()))
                .andExpect(jsonPath("$.data.tracked").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].optionId").value(first.toString()))
                .andExpect(jsonPath("$.data.items[0].registered").value(false))
                .andExpect(jsonPath("$.data.items[0].stockTotal").value(0))
                .andExpect(jsonPath("$.data.items[0].available").value(0))
                .andExpect(jsonPath("$.data.items[1].optionId").value(second.toString()))
                .andExpect(jsonPath("$.data.items[1].registered").value(true))
                .andExpect(jsonPath("$.data.items[1].stockTotal").value(8))
                .andExpect(jsonPath("$.data.items[1].stockReserved").value(1))
                .andExpect(jsonPath("$.data.items[1].stockSold").value(2))
                .andExpect(jsonPath("$.data.items[1].available").value(5))
                .andExpect(jsonPath("$.data.items[1].created").doesNotExist());
    }

    // 관리자 화면이 상품마다 이 탭을 부른다. 사전예약은 거절하지 않고 "세지 않음" 으로 답한다(쓰기는 409).
    @Test
    void getOnPreorderProductIsUntrackedAndEmpty() throws Exception {
        get(fixtures.preorderProduct().productId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tracked").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void getUnknownProductIs404AndCustomerIsForbidden() throws Exception {
        get(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/products/{productId}/stock", product.productId())
                        .with(TestAuth.customer(TestIds.id(1))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownProductIs404() throws Exception {
        put(UUID.randomUUID(), body(first, 1), TestAuth.admin())
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
        UUID foreign = fixtures.inStockProduct(1).optionIds().get(0);

        put(product.productId(), """
                {"items":[{"optionId":"%s","stockTotal":1},{"optionId":"%s","stockTotal":1}]}
                """.formatted(first, foreign), TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations.length()").value(1))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("items[1].optionId"));

        assertThat(reader.findByOptionIds(List.of(first, foreign))).isEmpty();
    }

    // 그 상품의 옵션으로 보낸다. 남의 옵션이면 소속 검사로도 400 이라 중복 검사를 증명하지 못한다.
    @Test
    void sameOptionTwiceIs400AtSecondPositionOnPutAndPost() throws Exception {
        String twice = """
                {"items":[{"optionId":"%s","stockTotal":1},{"optionId":"%s","stockTotal":2}]}
                """.formatted(first, first);

        put(product.productId(), twice, TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("items[1].optionId"));
        post(product.productId(), twice)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("items[1].optionId"));

        assertThat(reader.findByOptionIds(List.of(first))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"items\":[]}",
            "{\"items\":[null]}",
            "{\"items\":[{\"stockTotal\":1}]}",
            "{\"items\":[{\"optionId\":\"{option}\",\"stockTotal\":-1}]}",
            "{\"items\":[{\"optionId\":\"{option}\"}]}"
    })
    void malformedRequestIs400(String body) throws Exception {
        put(product.productId(), body.replace("{option}", first.toString()), TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // 소수 · int 를 넘는 수량은 조용히 잘리거나 넘치지 않고 거절된다. 잘리면 다른 수량이 들어간다.
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"items\":[{\"optionId\":\"%s\",\"stockTotal\":10.7}]}",
            "{\"items\":[{\"optionId\":\"%s\",\"stockTotal\":2147483648}]}"
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
        put(product.productId(), "{\"items\":[{\"optionId\":\"%s\",\"stockTotal\":%s}]}".formatted(first, token),
                TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(reader.findByOptionIds(List.of(first))).isEmpty();
    }

    /** 옵션 id 는 UUID 문자열만 받는다. %s 자리에는 실제 옵션의 하이픈 없는 표기를 넣는다. */
    @ParameterizedTest
    @ValueSource(strings = {"12", "12.5", "true", "\"12\"", "\"%s\"", "null"})
    void nonUuidOptionIdIs400(String template) throws Exception {
        put(product.productId(), "{\"items\":[{\"optionId\":%s,\"stockTotal\":1}]}".formatted(
                template.formatted(first.toString().replace("-", ""))), TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(reader.findByOptionIds(List.of(first))).isEmpty();
    }

    @Test
    void moreThanMaxItemsIs400() throws Exception {
        String items = String.join(",", IntStream.rangeClosed(1, StockRequest.MAX_ITEMS + 1)
                .mapToObj(n -> "{\"optionId\":\"%s\",\"stockTotal\":1}".formatted(UUID.randomUUID())).toList());

        put(product.productId(), "{\"items\":[" + items + "]}", TestAuth.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nonUuidProductIdIs400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/admin/products/abc/stock")
                        .contentType(MediaType.APPLICATION_JSON).content(body(first, 1)).with(TestAuth.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void customerIsForbidden() throws Exception {
        put(product.productId(), body(first, 1), TestAuth.customer(TestIds.id(1)))
                .andExpect(status().isForbidden());

        assertThat(reader.findByOptionIds(List.of(first))).isEmpty();
    }

    private ResultActions put(UUID productId, String body, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/admin/products/{productId}/stock", productId)
                .contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private ResultActions get(UUID productId) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/products/{productId}/stock", productId)
                .with(TestAuth.admin()));
    }

    private ResultActions post(UUID productId, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/products/{productId}/stock", productId)
                .contentType(MediaType.APPLICATION_JSON).content(body).with(TestAuth.admin()));
    }

    private static String body(UUID optionId, int total) {
        return "{\"items\":[{\"optionId\":\"%s\",\"stockTotal\":%d}]}".formatted(optionId, total);
    }
}
