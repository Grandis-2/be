package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 대상 이름 클라이언트의 HTTP 구현 고정(JDK)과 시간 제한이 실제 HTTP 에서 설정대로 도는지, 응답이 Lookup 으로 바르게 바뀌는지(order 의 HttpClientConfigTest 와
 * 같은 방식). MockRestServiceServer 는 요청 팩토리를 바꿔 끼우므로 구현 · 시간 제한을 볼 수 없어 로컬 HTTP 서버를 쓴다.
 */
@DisplayName("대상 이름 HTTP 클라이언트 — JDK 고정 · 읽기 1초 · 응답 해석")
class AdminAlertHttpClientConfigTest {

    // 멈춤은 기한의 3배라 CI 부하에도 결과가 뒤집히지 않는다
    static final long READ_TIMEOUT_MILLIS = 1_000;
    static final long STALL_MILLIS = 3_000;
    static final UUID FOUND = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    static final UUID MISSING = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");
    static final UUID STALLED = UUID.fromString("00000000-0000-0000-0000-00000000cccc");

    HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            try {
                if (path.endsWith(FOUND.toString())) {
                    respond(exchange, 200, "{\"success\":true,\"data\":{\"title\":\"아이폰 18 Pro\",\"extra\":{\"a\":1}}}");
                } else if (path.endsWith(STALLED.toString())) {
                    Thread.sleep(STALL_MILLIS);
                    respond(exchange, 200, "{\"success\":true,\"data\":{\"title\":\"늦음\"}}");
                } else {
                    respond(exchange, 404, "{\"success\":false,\"data\":null,\"error\":{\"code\":\"NOT_FOUND\"}}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 시간 초과로 클라이언트가 먼저 끊었다
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    ApplicationContextRunner runner() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
                        HttpServiceClientPropertiesAutoConfiguration.class, HttpServiceClientAutoConfiguration.class))
                .withUserConfiguration(AdminAlertHttpClientConfig.class)
                .withBean(TargetNames.class)
                .withPropertyValues(group(TargetNames.CATALOG, baseUrl))
                .withPropertyValues(group(TargetNames.ORDER, baseUrl));
    }

    /** 시간 제한은 MemberDefaults 의 기본값과 같은 값을 준다(이 시험은 member 를 띄우지 않는다). */
    private static String[] group(String name, String baseUrl) {
        String prefix = "spring.http.serviceclient." + name;
        return new String[] {prefix + ".base-url=" + baseUrl, prefix + ".connect-timeout=300ms",
                prefix + ".read-timeout=" + READ_TIMEOUT_MILLIS + "ms"};
    }

    /*
     * 클래스패스에는 AWS SDK 가 끌어온 Apache HttpClient 5 가 있어, 고정이 없으면 자동설정이 그것을 고른다. 빈 종류만이 아니라 두 그룹의 실제 호출이
     * JDK 로 나가는지 본다 — 읽기 기한 초과의 원인이 JDK 의 HttpTimeoutException 이다(Apache 면 SocketTimeoutException).
     */
    @Test
    @DisplayName("두 그룹 모두 JDK 로 나가고 읽기 1초에서 끊긴다")
    void bothGroupsCallThroughJdkWithReadTimeout() {
        runner().run(context -> {
            assertThat(context.getBean(ClientHttpRequestFactoryBuilder.class)).isInstanceOf(JdkClientHttpRequestFactoryBuilder.class);
            long started = System.nanoTime();
            assertThat(rootCause(catchThrowable(() -> context.getBean(TargetClients.CatalogProductClient.class).product(STALLED))))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(STALL_MILLIS);
            assertThat(rootCause(catchThrowable(() -> context.getBean(TargetClients.DrawClient.class).draw(STALLED))))
                    .isInstanceOf(HttpTimeoutException.class);
        });
    }

    @Test
    @DisplayName("200 은 title 만 읽고(다른 칸 무시), 404 는 ABSENT, 시간 초과는 FAILED")
    void lookups() {
        runner().run(context -> {
            TargetNames names = context.getBean(TargetNames.class);
            assertThat(names.find(TargetKind.PRODUCT, FOUND.toString())).isEqualTo(new TargetNames.Lookup("아이폰 18 Pro", false));
            assertThat(names.find(TargetKind.DRAW, FOUND.toString())).isEqualTo(new TargetNames.Lookup("아이폰 18 Pro", false));
            assertThat(names.find(TargetKind.PRODUCT, MISSING.toString())).isEqualTo(TargetNames.Lookup.ABSENT);
            assertThat(names.find(TargetKind.PRODUCT, STALLED.toString())).isEqualTo(TargetNames.Lookup.FAILED);
        });
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
