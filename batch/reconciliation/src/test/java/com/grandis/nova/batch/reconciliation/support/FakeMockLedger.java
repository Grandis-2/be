package com.grandis.nova.batch.reconciliation.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mock 원장 목록 조회(GET /external/reservations)만 흉내 내는 HTTP 서버. 계약(Mock docs/api.md)대로 외부 키 순서로
 * size 건씩 주고, 더 남았으면 nextCursor 에 이 페이지 마지막 키를, 없으면 null 을 준다.
 * 페이지 번호는 cursor 앞의 행 수로 정한다 — 앞 페이지까지 (번호 − 1) × size 건이다.
 */
public final class FakeMockLedger implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final NavigableMap<String, Map<String, Object>> rows = new ConcurrentSkipListMap<>();
    private final Map<Integer, Integer> failures = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
    private final Map<Integer, String> rawBodies = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    private FakeMockLedger(HttpServer server) {
        this.server = server;
    }

    public static FakeMockLedger start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeMockLedger fake = new FakeMockLedger(server);
            server.createContext("/external/reservations", fake::handle);
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
        rows.clear();
        failures.clear();
        failuresLeft.clear();
        rawBodies.clear();
        requests.set(0);
    }

    /** 등록된 행(ACTIVE) */
    public void active(String key, String number, String customerId, String productId, String sku, Instant confirmedAt) {
        put(key, number, customerId, productId, sku, "ACTIVE", confirmedAt, null);
    }

    /** 등록 전에 취소만 들어온 키(취소 표식) */
    public void cancelMarker(String key, Instant canceledAt) {
        put(key, null, null, null, null, "CANCELED", null, canceledAt);
    }

    public void put(String key, String number, String customerId, String productId, String sku, String status,
                    Instant confirmedAt, Instant canceledAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("externalKey", key);
        row.put("externalNumber", number);
        row.put("customerId", customerId);
        row.put("productId", productId);
        row.put("sku", sku);
        row.put("status", status);
        row.put("confirmedAt", confirmedAt == null ? null : confirmedAt.toString());
        row.put("canceledAt", canceledAt == null ? null : canceledAt.toString());
        rows.put(key, row);
    }

    /** 이 페이지를 부르면 HTTP 상태만 돌려준다 */
    public void failAtPage(int page, int status) {
        failures.put(page, status);
    }

    /** 이 페이지를 처음 times 번 부를 때만 HTTP 상태를 돌려주고, 그 뒤에는 정상으로 준다(일시 실패) */
    public void failAtPage(int page, int status, int times) {
        failuresLeft.put(page, new AtomicInteger(times));
        failures.put(page, status);
    }

    /** 이 페이지를 부르면 본문을 그대로 돌려준다(200) */
    public void respondAtPage(int page, String body) {
        rawBodies.put(page, body);
    }

    public int requests() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        int size = Integer.parseInt(query.getOrDefault("size", "500"));
        String cursor = query.get("cursor");
        NavigableMap<String, Map<String, Object>> after = cursor == null ? rows : rows.tailMap(cursor, false);
        int page = (cursor == null ? 0 : rows.headMap(cursor, true).size()) / size + 1;

        Integer failure = failures.get(page);
        AtomicInteger left = failuresLeft.get(page);
        if (failure != null && (left == null || left.getAndDecrement() > 0)) {
            respond(exchange, failure, "{\"errorCode\":\"FAKE\"}");
            return;
        }
        String raw = rawBodies.get(page);
        if (raw != null) {
            respond(exchange, 200, raw);
            return;
        }
        List<Map<String, Object>> items = new ArrayList<>(after.values().stream().limit(size).toList());
        boolean more = after.size() > size;
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("nextCursor", more ? items.getLast().get("externalKey") : null);
        respond(exchange, 200, JSON.writeValueAsString(body));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
