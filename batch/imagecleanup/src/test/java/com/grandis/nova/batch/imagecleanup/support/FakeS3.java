package com.grandis.nova.batch.imagecleanup.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S3 DeleteObject 만 흉내 내는 HTTP 서버. 경로 방식 주소(DELETE /{bucket}/{key})를 받아 키를 남기고 204 를 준다.
 * 키마다 처음 몇 번은 정한 상태로 실패하게 할 수 있다 — SDK 의 재시도까지 합친 시도 수를 볼 수 있다.
 */
public final class FakeS3 implements AutoCloseable {

    private final HttpServer server;
    private final String bucket;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();

    private FakeS3(HttpServer server, String bucket) {
        this.server = server;
        this.bucket = bucket;
    }

    public static FakeS3 start(String bucket) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeS3 fake = new FakeS3(server, bucket);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public URI url() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    public void reset() {
        requests.clear();
        deleted.clear();
        failures.clear();
        failuresLeft.clear();
    }

    /** 이 키의 삭제를 처음 times 번은 HTTP 상태로 거절하고, 그 뒤에는 지운다 */
    public void failDelete(String key, int status, int times) {
        failures.put(key, status);
        failuresLeft.put(key, new AtomicInteger(times));
    }

    /** 받은 삭제 요청의 키(실패한 시도 포함, 받은 순서) */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** 지운 키(받은 순서) */
    public List<String> deleted() {
        return List.copyOf(deleted);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String prefix = "/" + bucket + "/";
            String path = exchange.getRequestURI().getRawPath();
            if (!"DELETE".equals(exchange.getRequestMethod()) || !path.startsWith(prefix)) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            String key = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
            requests.add(key);
            AtomicInteger left = failuresLeft.get(key);
            if (left != null && left.getAndDecrement() > 0) {
                byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>InternalError</Code>"
                        + "<Message>fake failure</Message><RequestId>fake</RequestId></Error>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(failures.get(key), body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            deleted.add(key);
            exchange.sendResponseHeaders(204, -1);
        }
    }
}
