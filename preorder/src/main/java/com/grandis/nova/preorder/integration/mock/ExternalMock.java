package com.grandis.nova.preorder.integration.mock;

import com.grandis.nova.preorder.integration.DependencyGuard;
import com.grandis.nova.preorder.integration.DependencyUnavailableException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
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
 * 관리자 요청 스레드가 응답을 기다리므로 catalog · order 동기 호출처럼 서킷 브레이커 · 동시 호출 상한(DependencyGuard, 이름 mock)을 거친다.
 *
 * 다시 보내지 않는다. 못 받았거나 응답이 계약과 다르거나 회로가 열려 있으면 {@link ExternalMockUnavailable} 을 던지고, 다시 할지는
 * 관리자가 정한다. 키로 취소는 여러 번 보내도 효과가 한 번이라(Mock 계약) 다시 눌러도 안전하다.
 * 로그에는 키(예약 토큰) · 응답 본문 · 요청 주소를 남기지 않는다.
 */
public class ExternalMock {

    /** 정합성 검사의 키로 취소. Mock 은 사유를 저장하지 않고 받기만 한다 */
    public static final String GHOST_COMPENSATION = "GHOST_COMPENSATION";

    static final String DEPENDENCY = "mock";
    static final String LOOKUP_PATH = "/external/reservations/by-key/{key}";
    static final String CANCEL_PATH = "/external/cancellations";
    private static final Logger log = LoggerFactory.getLogger(ExternalMock.class);

    private final RestClient rest;
    private final JsonMapper json;
    private final int maxBody;
    private final DependencyGuard guard;

    /**
     * @param rest    Mock 주소와 제한 시간이 걸린 클라이언트
     * @param maxBody 응답 본문을 읽는 상한(바이트)
     */
    public ExternalMock(RestClient rest, JsonMapper json, int maxBody, DependencyGuard guard) {
        this.rest = rest;
        this.json = json;
        this.maxBody = maxBody;
        this.guard = guard;
    }

    /** 그 키의 지금 상태. 취소 표식이 있으면 등록이 남아 있어도 취소다 */
    public MockKeyState lookup(String key) {
        return guarded(() -> {
            Reply reply = call("키로 조회", rest.get().uri(LOOKUP_PATH, key));
            if (reply.status() == 404 && "NOT_FOUND".equals(text(reply.body(), "errorCode"))) {
                return MockKeyState.ABSENT;
            }
            if (reply.status() != 200 || reply.body() == null) {
                throw unexpected("키로 조회", reply);
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
            throw unexpected("키로 조회", reply);
        });
    }

    /** 키로 취소한다. 등록이 없어도 취소 표식이 남는다(Mock 계약 — 키로 취소는 항상 200) */
    public void cancel(String key, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalKey", key);
        body.put("reservationNo", null);
        body.put("reason", reason);
        guarded(() -> {
            Reply reply = call("키로 취소", rest.post().uri(CANCEL_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsBytes(body)));
            if (reply.status() != 200) {
                throw unexpected("키로 취소", reply);
            }
            return null;
        });
    }

    /** 회로가 열렸거나 동시 호출 상한이 차면 부르지 않고 바로 실패로 돌려준다 */
    private <T> T guarded(Supplier<T> call) {
        try {
            return guard.call(DEPENDENCY, call);
        } catch (DependencyUnavailableException e) {
            throw new ExternalMockUnavailable("Mock 호출을 잠시 막음(회로 열림 · 동시 호출 상한) — 잠시 뒤 다시", e);
        }
    }

    private Reply call(String what, RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.accept(MediaType.APPLICATION_JSON).exchange((sent, response) ->
                    new Reply(response.getStatusCode().value(), object(response.getBody().readNBytes(maxBody))));
        } catch (RestClientException e) {
            // 예외 메시지에 요청 주소(키가 든 경로)가 있어 예외는 찍지 않는다. 원인은 cause 로 넘긴다
            log.warn("Mock {} 실패 — {} ({})", what, reason(e), rootCause(e).getClass().getName());
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

    private static ExternalMockUnavailable unexpected(String what, Reply reply) {
        log.warn("Mock {} 응답이 계약과 다름 HTTP {}", what, reply.status());
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

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    /** @param body 본문이 JSON 객체가 아니면 null */
    private record Reply(int status, JsonNode body) {
    }
}
