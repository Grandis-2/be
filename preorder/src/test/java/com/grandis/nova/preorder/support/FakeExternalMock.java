package com.grandis.nova.preorder.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 외부 예약 Mock 의 키로 조회(GET /external/reservations/by-key/{key}) · 키로 취소(POST /external/cancellations)만 흉내 내는
 * HTTP 서버. 응답 모양은 Mock docs/api.md 와 같다. 모르는 키는 기록 없음(404 NOT_FOUND)이다.
 */
public final class FakeExternalMock implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LOOKUP = "/external/reservations/by-key/";

    private final HttpServer server;
    private final Map<String, String> states = new ConcurrentHashMap<>();
    private final List<JsonNode> cancels = new CopyOnWriteArrayList<>();
    private final AtomicInteger lookups = new AtomicInteger();
    private volatile int cancelStatus = 200;

    private FakeExternalMock(HttpServer server) {
        this.server = server;
    }

    public static FakeExternalMock start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeExternalMock fake = new FakeExternalMock(server);
            server.createContext(LOOKUP, fake::lookup);
            server.createContext("/external/cancellations", fake::cancel);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void reset() {
        states.clear();
        cancels.clear();
        lookups.set(0);
        cancelStatus = 200;
    }

    /** 활성 등록이 있는 키 */
    public void active(String key) {
        states.put(key, "ACTIVE");
    }

    /** 등록 후 취소됐거나 취소 표식만 있는 키 */
    public void canceled(String key) {
        states.put(key, "CANCELED");
    }

    /** 키로 취소가 이 상태로 답한다(본문 없음). 200 이면 정상 */
    public void cancelAnswers(int status) {
        cancelStatus = status;
    }

    /** 받은 키로 취소 본문들 */
    public List<JsonNode> cancels() {
        return List.copyOf(cancels);
    }

    public int lookups() {
        return lookups.get();
    }

    public String state(String key) {
        return states.get(key);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void lookup(HttpExchange exchange) throws IOException {
        lookups.incrementAndGet();
        String key = exchange.getRequestURI().getPath().substring(LOOKUP.length());
        String state = states.get(key);
        if (state == null) {
            respond(exchange, 404, Map.of("errorCode", "NOT_FOUND", "errorMessage", "키에 대한 기록이 없습니다."));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalKey", key);
        if (state.equals("ACTIVE")) {
            body.put("cancelMarkerAt", null);
            body.put("registrations", List.of(registration("ACTIVE", null)));
            body.put("storedOutcome", "SUCCESS");
        } else {
            body.put("cancelMarkerAt", "2026-10-13T00:00:00.000Z");
            body.put("registrations", List.of());
            body.put("storedOutcome", null);
        }
        respond(exchange, 200, body);
    }

    private void cancel(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        cancels.add(request);
        if (cancelStatus != 200) {
            exchange.sendResponseHeaders(cancelStatus, -1);
            exchange.close();
            return;
        }
        String key = request.get("externalKey").asString();
        boolean hadActive = "ACTIVE".equals(states.put(key, "CANCELED"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalKey", key);
        body.put("externalNumbers", hadActive ? List.of("R-20261013-0000000001") : List.of());
        body.put("hadActiveRegistration", hadActive);
        body.put("cancelMarkerAt", "2026-10-13T00:00:00.000Z");
        body.put("canceledAt", "2026-10-13T00:00:00.000Z");
        respond(exchange, 200, body);
    }

    private static Map<String, Object> registration(String status, String canceledAt) {
        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("externalNumber", "R-20261013-0000000001");
        registration.put("status", status);
        registration.put("confirmedAt", "2026-10-12T00:00:00.000Z");
        registration.put("canceledAt", canceledAt);
        return registration;
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
