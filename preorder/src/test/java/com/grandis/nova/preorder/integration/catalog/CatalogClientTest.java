package com.grandis.nova.preorder.integration.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 계약(contracts/preorder-internal.md)의 경로와 응답 봉투를 그대로 읽는지. */
class CatalogClientTest {

    MockRestServiceServer server;
    CatalogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog");
        server = MockRestServiceServer.bindTo(builder).build();
        client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(CatalogClient.class);
    }

    @Test
    void 봉투의_data_에서_상품과_옵션을_읽는다() {
        server.expect(requestTo("http://catalog/internal/products/7/options"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"success":true,
                         "data":{"productId":7,"title":"Nova 1","saleMode":"PREORDER","status":"ACTIVE",
                                 "options":[{"optionId":70,"sku":"NOVA-1-BLK-256","title":"블랙 / 256GB",
                                             "price":1250000,"status":"ACTIVE"}]},
                         "error":null,"timestamp":"2026-09-22T00:00:00Z","traceId":"t-1"}
                        """, MediaType.APPLICATION_JSON));

        ProductCatalog product = client.getProduct(7L).data();

        assertThat(product.saleMode()).isEqualTo("PREORDER");
        assertThat(product.snapshot(70L)).get().satisfies(option -> {
            assertThat(option.sku()).isEqualTo("NOVA-1-BLK-256");
            assertThat(option.price()).isEqualByComparingTo(new BigDecimal("1250000"));
        });
        server.verify();
    }

    @Test
    void 없는_상품이면_404_예외다() {
        server.expect(requestTo("http://catalog/internal/products/404/options"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getProduct(404L)).isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
