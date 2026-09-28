package com.grandis.nova.order.client.preorder;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class PreorderReaderTest {

    static final String PREORDER_UUID = "0b8f6a3e-5a8c-4d59-9a53-3c1f0e0f7a11";

    PreorderClient client = mock(PreorderClient.class);
    PreorderReader reader = new PreorderReader(client);

    @Test
    void returnsSnapshot() {
        PreorderSnapshot snapshot = new PreorderSnapshot(1L, PREORDER_UUID, 7L, 3L, 30L, "Nova 1", "블랙",
                new BigDecimal("1000"), "PAYABLE", Instant.now());
        given(client.getPreorder(PREORDER_UUID)).willReturn(ApiResponse.ok(snapshot));

        assertThat(reader.find(PREORDER_UUID)).contains(snapshot);
    }

    @Test
    void notFoundIsEmpty() {
        given(client.getPreorder(PREORDER_UUID)).willThrow(clientError(HttpStatus.NOT_FOUND));

        assertThat(reader.find(PREORDER_UUID)).isEmpty();
    }

    // 다시 불러도 같은 결과인 연동 오류다. 사용자 잘못이 아니므로 400 이 아니라 500 이 된다.
    @Test
    void otherClientErrorIsIntegrationError() {
        given(client.getPreorder(PREORDER_UUID)).willThrow(clientError(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> reader.find(PREORDER_UUID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void timeoutIsDependencyUnavailable() {
        given(client.getPreorder(PREORDER_UUID)).willThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> reader.find(PREORDER_UUID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void serverErrorIsDependencyUnavailable() {
        given(client.getPreorder(PREORDER_UUID)).willThrow(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null));

        assertThatThrownBy(() -> reader.find(PREORDER_UUID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), null, null, null);
    }
}
