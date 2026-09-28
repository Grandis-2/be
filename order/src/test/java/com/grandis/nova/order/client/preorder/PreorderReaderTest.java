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
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class PreorderReaderTest {

    static final String PREORDER_UUID = "0b8f6a3e-5a8c-4d59-9a53-3c1f0e0f7a11";
    // 전달할 세션 토큰 자리. 실제 토큰 모양이 아니다.
    static final String SESSION = "reader-test-user-7";

    PreorderClient client = mock(PreorderClient.class);
    PreorderReader reader = new PreorderReader(client);

    @Test
    void returnsPayabilityAndForwardsSessionToken() {
        Instant payableFrom = Instant.now();
        PreorderPayability payability = new PreorderPayability(PREORDER_UUID, 1L, 7L, 3L, 30L, "Nova 1", "블랙",
                new BigDecimal("1000"), "PAYABLE", payableFrom, payableFrom.plus(Duration.ofHours(24)), true, null);
        given(client.getPayability(PREORDER_UUID, SESSION)).willReturn(ApiResponse.ok(payability));

        assertThat(reader.find(PREORDER_UUID, SESSION)).contains(payability);
    }

    @Test
    void notFoundIsEmpty() {
        given(client.getPayability(PREORDER_UUID, SESSION)).willThrow(clientError(HttpStatus.NOT_FOUND));

        assertThat(reader.find(PREORDER_UUID, SESSION)).isEmpty();
    }

    // 남의 예약(403)도 없는 것과 같이 비운다 — 사용자에게는 404 로 존재를 숨긴다.
    @Test
    void forbiddenIsEmpty() {
        given(client.getPayability(PREORDER_UUID, SESSION)).willThrow(clientError(HttpStatus.FORBIDDEN));

        assertThat(reader.find(PREORDER_UUID, SESSION)).isEmpty();
    }

    // 토큰 문제는 사용자에게 그대로 401 이다.
    @Test
    void unauthorizedIsUnauthenticated() {
        given(client.getPayability(PREORDER_UUID, SESSION)).willThrow(clientError(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> reader.find(PREORDER_UUID, SESSION))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.UNAUTHENTICATED));
    }

    // 다시 불러도 같은 결과인 연동 오류다. 사용자 잘못이 아니므로 400 이 아니라 500 이 된다.
    @Test
    void otherClientErrorIsIntegrationError() {
        given(client.getPayability(PREORDER_UUID, SESSION)).willThrow(clientError(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> reader.find(PREORDER_UUID, SESSION)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void timeoutIsDependencyUnavailable() {
        given(client.getPayability(PREORDER_UUID, SESSION)).willThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> reader.find(PREORDER_UUID, SESSION))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void serverErrorIsDependencyUnavailable() {
        given(client.getPayability(PREORDER_UUID, SESSION)).willThrow(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null));

        assertThatThrownBy(() -> reader.find(PREORDER_UUID, SESSION))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), null, null, null);
    }
}
