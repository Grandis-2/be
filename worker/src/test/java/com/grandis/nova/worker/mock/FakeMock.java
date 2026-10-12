package com.grandis.nova.worker.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 시험이 응답 · 지연을 정하는 가짜 외부 mock. 받은 요청을 기록한다. */
public final class FakeMock implements AutoCloseable {

    public interface Handler {

        void handle(HttpExchange exchange) throws Exception;
    }

    public record Received(String method, String path, String idempotencyKey, String contentType, String accept,
                    String acceptEncoding, String body) {
    }

    private final HttpServer server;
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private volatile Handler handler = json(500, "{}");

    public FakeMock() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Accept"),
                    exchange.getRequestHeaders().getFirst("Accept-Encoding"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    public void respond(Handler handler) {
        this.handler = handler;
    }

    public List<Received> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static Handler json(int status, String body) {
        return body(status, "application/json", body);
    }

    public static Handler html(int status) {
        return body(status, "text/html", "<html><body><center><h1>" + status + "</h1></center></body></html>");
    }

    public static Handler body(int status, String contentType, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        };
    }

    public static Handler empty(int status) {
        return exchange -> exchange.sendResponseHeaders(status, -1);
    }

    public static Handler header(String name, String value, Handler next) {
        return exchange -> {
            exchange.getResponseHeaders().add(name, value);
            next.handle(exchange);
        };
    }

    public static Handler after(Duration delay, Handler next) {
        return exchange -> {
            Thread.sleep(delay);
            next.handle(exchange);
        };
    }

    /** 부를 때마다 다음 응답을 준다. 다 쓰면 마지막 응답을 되풀이한다. */
    public static Handler inOrder(Handler... handlers) {
        AtomicInteger calls = new AtomicInteger();
        return exchange -> handlers[Math.min(calls.getAndIncrement(), handlers.length - 1)].handle(exchange);
    }

    /** 응답 없이 연결을 끊는다. */
    public static Handler drop() {
        return exchange -> {
        };
    }
}
