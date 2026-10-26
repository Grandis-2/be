package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.security.BearerTokens;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 시험용 SSE 클라이언트(회원 알림 시험의 SseClient 와 같고 경로만 받는다). 화면처럼 Authorization 헤더 · Accept: text/event-stream 으로 연결하고, 받은 줄을 이벤트로 묶어 줄에 쌓는다.
 * 200 이 아니면 본문을 통째로 읽어 둔다(오류 봉투). 서버가 닫으면 {@link #END} 가 들어온다.
 */
final class SseClient implements AutoCloseable {

    static final Event END = new Event("(end)", null);
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** @param name 이벤트 이름. 주석 줄이면 "(comment)" */
    record Event(String name, String data) {

        boolean comment() {
            return "(comment)".equals(name);
        }
    }

    final HttpResponse<Stream<String>> response;
    final String errorBody;
    private final BlockingQueue<Event> received = new LinkedBlockingQueue<>();
    /** 받은 줄 그대로(사건 형식이 아닌 줄도) — 스트림 뒤에 다른 본문이 붙지 않았는지 볼 때 쓴다 */
    final List<String> lines = new CopyOnWriteArrayList<>();

    private SseClient(HttpResponse<Stream<String>> response) {
        this.response = response;
        if (response.statusCode() != 200) {
            try (Stream<String> lines = response.body()) {
                errorBody = lines.collect(Collectors.joining("\n"));
            }
            return;
        }
        errorBody = null;
        Thread.ofVirtual().name("sse-client").start(this::read);
    }

    static SseClient open(int port, String path, String accessToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream");
        if (accessToken != null) {
            request.header(BearerTokens.HEADER, BearerTokens.value(accessToken));
        }
        try {
            return new SseClient(HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofLines()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    int status() {
        return response.statusCode();
    }

    String header(String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    /** 다음 이벤트(주석 포함). 시간 안에 없으면 null, 서버가 닫았으면 {@link #END}. */
    Event next(Duration timeout) throws InterruptedException {
        return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 주석을 건너뛴 다음 이벤트. */
    Event nextEvent(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Event event = next(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            if (event == null || !event.comment()) {
                return event;
            }
        }
    }

    /** 시간 안에 서버가 닫으면 true. 그 사이 받은 것은 버린다. */
    boolean awaitEnd(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Event event = next(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            if (event == END) {
                return true;
            }
            if (event == null) {
                return false;
            }
        }
    }

    private void read() {
        String name = null;
        StringBuilder data = null;
        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                this.lines.add(line);
                if (line.isEmpty()) {
                    if (data != null) {
                        received.add(new Event(name == null ? "message" : name, data.toString()));
                    }
                    name = null;
                    data = null;
                } else if (line.startsWith(":")) {
                    received.add(new Event("(comment)", line.substring(1)));
                } else if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:")) {
                    String value = line.substring("data:".length()).strip();
                    data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
                }
            }
        } catch (RuntimeException e) {
            // 연결이 끊기면 줄 읽기가 예외로 끝난다 — 닫힌 것으로 본다
        } finally {
            received.add(END);
        }
    }

    @Override
    public void close() {
        if (errorBody == null) {
            response.body().close();
        }
    }
}
