package com.grandis.nova.preorder.integration.order;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.ErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.support.DependencyGuards;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCancelabilityCheckerTest {

    static final String TOKEN = "9f1c2d3e";
    static final String ACCESS_TOKEN = "access-token";

    @Test
    void 취소_가능하면_통과하고_받은_토큰을_Bearer_헤더로_싣는다() {
        FakeOrderClient client = FakeOrderClient.answering(new Cancelability(TOKEN, null, true, null));

        assertThatCode(() -> new OrderCancelabilityChecker(client, DependencyGuards.passThrough()).requireCancelable(TOKEN, ACCESS_TOKEN))
                .doesNotThrowAnyException();
        assertThat(client.receivedAuthorization).isEqualTo("Bearer " + ACCESS_TOKEN);
    }

    @Test
    void 토큰이_없으면_인증_헤더를_싣지_않는다() {
        FakeOrderClient client = FakeOrderClient.answering(new Cancelability(TOKEN, null, true, null));

        new OrderCancelabilityChecker(client, DependencyGuards.passThrough()).requireCancelable(TOKEN, null);

        assertThat(client.receivedAuthorization).isNull();
    }

    @Test
    void 배송이_시작됐으면_409_와_주문_상태() {
        FakeOrderClient client = FakeOrderClient.answering(new Cancelability(TOKEN, "SHIPPED", false, "SHIPPED"));

        assertThatThrownBy(() -> new OrderCancelabilityChecker(client, DependencyGuards.passThrough()).requireCancelable(TOKEN, ACCESS_TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(PreorderErrorCode.PREORDER_NOT_CANCELABLE);
                    assertThat(e.details()).containsEntry("reason", "orderStatus=SHIPPED");
                });
    }

    @Test
    void 응답이_없거나_5xx_면_503() {
        assertThat(errorOf(new ResourceAccessException("timeout"))).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        assertThat(errorOf(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null)))
                .isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void 토큰_문제는_401_주인이_아니면_존재를_숨기고_404() {
        assertThat(errorOf(clientError(HttpStatus.UNAUTHORIZED))).isEqualTo(CommonErrorCode.UNAUTHENTICATED);
        assertThat(errorOf(clientError(HttpStatus.FORBIDDEN))).isEqualTo(PreorderErrorCode.PREORDER_NOT_FOUND);
    }

    @Test
    void 그_밖의_4xx_는_재시도_안내가_아니라_연동_오류다() {
        FakeOrderClient client = FakeOrderClient.failing(clientError(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> new OrderCancelabilityChecker(client, DependencyGuards.passThrough()).requireCancelable(TOKEN, ACCESS_TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    void 판정_없이_성공_응답이면_연동_오류다() {
        FakeOrderClient client = FakeOrderClient.answering(null);

        assertThatThrownBy(() -> new OrderCancelabilityChecker(client, DependencyGuards.passThrough()).requireCancelable(TOKEN, ACCESS_TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TOKEN);
    }

    private static ErrorCode errorOf(RestClientException failure) {
        try {
            new OrderCancelabilityChecker(FakeOrderClient.failing(failure), DependencyGuards.passThrough()).requireCancelable(TOKEN, ACCESS_TOKEN);
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("업무 오류가 나야 한다");
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), null, null, null);
    }

    static final class FakeOrderClient implements OrderClient {

        private final Cancelability answer;
        private final RestClientException failure;
        String receivedAuthorization;

        private FakeOrderClient(Cancelability answer, RestClientException failure) {
            this.answer = answer;
            this.failure = failure;
        }

        static FakeOrderClient answering(Cancelability answer) {
            return new FakeOrderClient(answer, null);
        }

        static FakeOrderClient failing(RestClientException failure) {
            return new FakeOrderClient(null, failure);
        }

        @Override
        public ApiResponse<Cancelability> getCancelability(String preorderId, String authorization) {
            receivedAuthorization = authorization;
            if (failure != null) {
                throw failure;
            }
            return ApiResponse.ok(answer);
        }
    }
}
