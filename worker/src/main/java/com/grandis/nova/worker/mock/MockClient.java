package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.mock.MockReply.TransportFailure;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * 외부 mock 을 부르고 돌려준 것을 그대로 건넨다. 상태가 4xx · 5xx 여도 던지지 않고, 판정은 {@link MockJudge} 가 한다.
 * 다시 보내기와 리다이렉트 따라가기는 하지 않는다 — 재시도는 worker 가 정하고, 3xx 는 설정 오류로 드러나야 한다.
 */
class MockClient {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient rest;
    private final JsonMapper json;
    private final int maxBody;

    /** @param maxBody 응답 본문을 읽는 상한(바이트). 엉뚱한 서버가 큰 본문을 보내도 여기까지만 읽는다 */
    MockClient(RestClient rest, JsonMapper json, int maxBody) {
        this.rest = rest;
        this.json = json;
        this.maxBody = maxBody;
    }

    /** @param key 멱등 키. 본문의 ourReservationId 와 같아야 한다 */
    MockReply register(String key, JsonNode request) {
        return exchange(rest.post().uri("/external/reservations")
                .header(IDEMPOTENCY_KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsBytes(request)));
    }

    MockReply lookup(String key) {
        return exchange(rest.get().uri("/external/reservations/by-key/{key}", key));
    }

    MockReply cancel(JsonNode request) {
        return exchange(rest.post().uri("/external/cancellations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsBytes(request)));
    }

    /** @param entryId 멱등 키(응모 id). 본문의 entryId 와 같아야 한다 */
    MockReply enterDraw(String entryId, JsonNode request) {
        return exchange(rest.post().uri("/external/draw-entries")
                .header(IDEMPOTENCY_KEY, entryId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsBytes(request)));
    }

    MockReply drawStatus(String drawId) {
        return exchange(rest.get().uri("/external/draws/{drawId}", drawId));
    }

    /** 멱등 키는 회차 id 다(경로). Idempotency-Key 헤더를 받지 않는다. */
    MockReply draw(String drawId, JsonNode request) {
        return exchange(rest.post().uri("/external/draws/{drawId}/draw", drawId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsBytes(request)));
    }

    private MockReply exchange(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.accept(MediaType.APPLICATION_JSON).exchange((sent, response) ->
                    MockReply.response(response.getStatusCode().value(),
                            object(response.getBody().readNBytes(maxBody)), retryAfter(response.getHeaders())));
        } catch (RestClientException e) {
            return MockReply.failed(failureOf(e));
        }
    }

    private JsonNode object(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 연결 시간 초과는 응답 시간 초과의 하위 형이라 먼저 가른다. 풀에서 연결을 못 얻은 것도 요청이 가지 않았으니 연결 실패다. */
    static TransportFailure failureOf(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof ConnectTimeoutException
                    || cause instanceof UnknownHostException || cause instanceof ConnectionRequestTimeoutException) {
                return TransportFailure.CONNECT;
            }
            if (cause instanceof SocketTimeoutException) {
                return TransportFailure.READ_TIMEOUT;
            }
        }
        return TransportFailure.BROKEN;
    }
}
