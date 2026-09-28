package com.grandis.nova.order.client.preorder;

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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 가정한 계약(NV-55 계획서 §4-1)의 경로와 응답 봉투를 그대로 읽는지. */
class PreorderClientTest {

    static final String PREORDER_UUID = "0b8f6a3e-5a8c-4d59-9a53-3c1f0e0f7a11";

    MockRestServiceServer server;
    PreorderClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://preorder");
        server = MockRestServiceServer.bindTo(builder).build();
        client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(PreorderClient.class);
    }

    @Test
    void readsSnapshotFromEnvelopeData() {
        server.expect(requestTo("http://preorder/internal/preorders/" + PREORDER_UUID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"success":true,
                         "data":{"id":11,"preorderId":"%s","customerId":7,"productId":3,"optionId":30,
                                 "productTitle":"Nova 1","optionTitle":"블랙 / 256GB","unitPrice":1250000,
                                 "status":"PAYABLE","payableFrom":"2026-09-25T01:02:03.123456Z"},
                         "error":null,"timestamp":"2026-09-25T00:00:00Z","traceId":"t-1"}
                        """.formatted(PREORDER_UUID), MediaType.APPLICATION_JSON));

        PreorderSnapshot snapshot = client.getPreorder(PREORDER_UUID).data();

        assertThat(snapshot).isEqualTo(new PreorderSnapshot(11L, PREORDER_UUID, 7L, 3L, 30L, "Nova 1", "블랙 / 256GB",
                new BigDecimal("1250000"), "PAYABLE", Instant.parse("2026-09-25T01:02:03.123456Z")));
        server.verify();
    }

    @Test
    void missingPreorderIsNotFound() {
        server.expect(requestTo("http://preorder/internal/preorders/" + PREORDER_UUID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getPreorder(PREORDER_UUID)).isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
