package com.grandis.nova.common.web.client;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 분류 규칙 자체. RestClient 가 실제로 올리는 예외 모양은 쓰는 서비스의 클라이언트 테스트(order PreorderClientTest)가 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class InternalCallFailuresTest {

    static final String DEPENDENCY = "preorder";
    static final String TARGET = "payability 3f2a";

    @Test
    void clientErrorBecomesIntegrationErrorWithStatusButNotTheCallersStatus(CapturedOutput output) {
        HttpClientErrorException cause = new HttpClientErrorException(HttpStatus.CONFLICT);

        IllegalStateException failure = InternalCallFailures.integrationError(DEPENDENCY, TARGET, cause);

        assertThat(failure).hasCause(cause).hasMessage("preorder 연동 오류: 409 CONFLICT");
        assertThat(output).contains("preorder 연동 오류 payability 3f2a status=409 CONFLICT");
    }

    // 본문을 변환하지 못하면 RestClient 는 RestClientException 으로 감싸 올린다(원인 HttpMessageNotReadableException).
    @Test
    void bodyThatCannotBeConvertedIsUnreadable() {
        RestClientException wrapped = new RestClientException("Error while extracting response",
                new HttpMessageNotReadableException("JSON parse error", new MockHttpInputMessage(new byte[0])));

        assertThat(InternalCallFailures.isUnreadableResponse(wrapped)).isTrue();
    }

    // 읽을 변환기가 없는 Content-Type 은 UnknownContentTypeException 자체로 온다.
    @Test
    void unknownContentTypeIsUnreadable() {
        UnknownContentTypeException unknown = new UnknownContentTypeException(String.class, MediaType.TEXT_HTML,
                HttpStatus.OK, "OK", new HttpHeaders(), "<html>gateway</html>".getBytes());

        assertThat(InternalCallFailures.isUnreadableResponse(unknown)).isTrue();
    }

    // 다시 부르면 나아질 수 있는 것은 읽을 수 없는 응답이 아니다 — 503 쪽으로 가야 한다.
    @Test
    void timeoutConnectionFailureAndServerErrorAreNotUnreadable() {
        assertThat(InternalCallFailures.isUnreadableResponse(
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")))).isFalse();
        assertThat(InternalCallFailures.isUnreadableResponse(
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))).isFalse();
        assertThat(InternalCallFailures.isUnreadableResponse(
                new RestClientException("other", new IOException("connection reset")))).isFalse();
    }

    @Test
    void unreadableResponseBecomesIntegrationError(CapturedOutput output) {
        RestClientException cause = new UnknownContentTypeException(String.class, MediaType.TEXT_HTML,
                HttpStatus.OK, "OK", new HttpHeaders(), new byte[0]);

        IllegalStateException failure = InternalCallFailures.unreadableResponse(DEPENDENCY, TARGET, cause);

        assertThat(failure).hasCause(cause).hasMessage("preorder 연동 오류: 응답을 읽을 수 없음");
        assertThat(output).contains("preorder 연동 오류 payability 3f2a 응답을 읽을 수 없음");
    }

    // 원인은 로그로만 남기고 사용자에게는 공통 503 만 보인다.
    @Test
    void unavailableBecomesDependencyUnavailable(CapturedOutput output) {
        BusinessException failure = InternalCallFailures.unavailable(DEPENDENCY, TARGET,
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertThat(failure.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        assertThat(failure).hasNoCause();
        assertThat(output).contains("preorder 호출 실패 payability 3f2a");
    }
}
