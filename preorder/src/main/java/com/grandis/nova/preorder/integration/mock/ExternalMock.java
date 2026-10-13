package com.grandis.nova.preorder.integration.mock;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 외부 예약 Mock 의 키로 조회 · 키로 취소(Mock docs/api.md). 정합성 조치에서 관리자가 누를 때만 부른다 — 예약 등록 · 취소 작업은 worker 가 한다.
 *
 * 다시 보내지 않는다. 못 받았거나 응답이 계약과 다르면 {@link ExternalMockUnavailable} 을 던지고, 다시 할지는 관리자가 정한다.
 * 키로 취소는 여러 번 보내도 효과가 한 번이라(Mock 계약) 다시 눌러도 안전하다.
 */
public class ExternalMock {

    /** 정합성 검사의 키로 취소. Mock 은 사유를 저장하지 않고 받기만 한다 */
    public static final String GHOST_COMPENSATION = "GHOST_COMPENSATION";

    static final String LOOKUP_PATH = "/external/reservations/by-key/{key}";
    static final String CANCEL_PATH = "/external/cancellations";
    private static final Logger log = LoggerFactory.getLogger(ExternalMock.class);

    private final RestClient rest;
    private final JsonMapper json;
    private final int maxBody;

    /**
     * @param rest    Mock 주소와 제한 시간이 걸린 클라이언트
     * @param maxBody 응답 본문을 읽는 상한(바이트)
     */
    public ExternalMock(RestClient rest, JsonMapper json, int maxBody) {
        this.rest = rest;
        this.json = json;
        this.maxBody = maxBody;
    }

    /** 그 키의 지금 상태. 취소 표식이 있으면 등록이 남아 있어도 취소다 */
    public MockKeyState lookup(String key) {
        Reply reply = call("키로 조회", rest.get().uri(LOOKUP_PATH, key));
        if (reply.status() == 404 && "NOT_FOUND".equals(text(reply.body(), "errorCode"))) {
            return MockKeyState.ABSENT;
        }
        if (reply.status() != 200 || reply.body() == null) {
            throw unexpected("키로 조회", reply, key);
        }
        if (text(reply.body(), "cancelMarkerAt") != null) {
            return MockKeyState.CANCELED;
        }
        JsonNode registrations = reply.body().get("registrations");
        if (registrations != null && registrations.isArray()) {
            for (JsonNode registration : registrations) {
                if ("ACTIVE".equals(text(registration, "status"))) {
                    return MockKeyState.ACTIVE;
                }
            }
        }
        throw unexpected("키로 조회", reply, key);
    }

    /** 키로 취소한다. 등록이 없어도 취소 표식이 남는다(Mock 계약 — 키로 취소는 항상 200) */
    public void cancel(String key, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalKey", key);
        body.put("reservationNo", null);
        body.put("reason", reason);
        Reply reply = call("키로 취소", rest.post().uri(CANCEL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsBytes(body)));
        if (reply.status() != 200) {
            throw unexpected("키로 취소", reply, key);
        }
    }

    private Reply call(String what, RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.accept(MediaType.APPLICATION_JSON).exchange((sent, response) ->
                    new Reply(response.getStatusCode().value(), object(response.getBody().readNBytes(maxBody))));
        } catch (RestClientException e) {
            log.warn("Mock {} 실패", what, e);
            throw new ExternalMockUnavailable("Mock " + what + " 응답을 받지 못함 — " + reason(e), e);
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

    private static ExternalMockUnavailable unexpected(String what, Reply reply, String key) {
        log.warn("Mock {} 응답이 계약과 다름 키 {} HTTP {} 본문 {}", what, key, reply.status(), reply.body());
        return new ExternalMockUnavailable("Mock " + what + " 응답이 계약과 다름(HTTP " + reply.status() + ")");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    static String reason(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return "시간 초과";
            }
            if (cause instanceof ConnectException) {
                return "연결 실패";
            }
        }
        return "통신 오류";
    }

    /** @param body 본문이 JSON 객체가 아니면 null */
    private record Reply(int status, JsonNode body) {
    }
}
