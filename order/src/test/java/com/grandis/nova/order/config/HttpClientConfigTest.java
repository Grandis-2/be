package com.grandis.nova.order.config;

import com.grandis.nova.common.security.AuthenticatedPrincipal;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.member.MemberClient;
import com.grandis.nova.order.client.payment.CaptureRequest;
import com.grandis.nova.order.client.payment.ConfirmRequest;
import com.grandis.nova.order.client.payment.PayableTarget;
import com.grandis.nova.order.client.payment.PaymentClient;
import com.grandis.nova.order.client.payment.PaymentConfirmClient;
import com.grandis.nova.order.client.payment.PaymentConfirmation;
import com.grandis.nova.order.client.payment.PaymentConfirmer;
import com.grandis.nova.order.client.payment.PaymentDeadLetterClient;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.TestIds;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientObservationAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 내부 API 클라이언트의 HTTP 구현 고정(JDK)과 결제 승인 그룹(payment-confirm)의 시간 예산이 실제 HTTP 에서 설정대로 도는지.
 * 관리자 결제 DLQ 는 자기 그룹(payment-admin)의 설정으로 나간다.
 * MockRestServiceServer 는 요청 팩토리를 바꿔 끼우므로 구현 · 타임아웃을 볼 수 없어 로컬 HTTP 서버를 쓴다.
 */
class HttpClientConfigTest {

    // 시간 기반 테스트의 여유. 멈춤은 기한의 3배라 CI 부하에도 결과가 뒤집히지 않는다
    static final long READ_TIMEOUT_MILLIS = 1_000;
    static final long STALL_MILLIS = 3_000;
    static final String PROVIDER_ORDER_ID = "6f1c2d3e-4b5a-4c7d-8e9f-0a1b2c3d4e5f";
    static final Order ORDER = new Order(TestIds.id(81), TestIds.id(7), OrderSource.PREORDER,
            TestIds.id(5), "9f1c2d3e-0000-4000-8000-000000000001", null, OrderStatus.AUTHORIZING, PROVIDER_ORDER_ID,
            new Money(new BigDecimal("1000")), null, null, null,
            null, new ShipTo("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null), 2, Instant.EPOCH,
            Instant.EPOCH);

    HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(STALL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    ApplicationContextRunner runner(String baseUrl) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
                        HttpServiceClientPropertiesAutoConfiguration.class, HttpServiceClientAutoConfiguration.class))
                .withUserConfiguration(HttpClientConfig.class)
                .withBean(PaymentConfirmer.class)
                .withPropertyValues(group("payment-confirm", baseUrl))
                .withPropertyValues(group("payment", baseUrl))
                .withPropertyValues(group("payment-admin", baseUrl))
                .withPropertyValues(group("payment-refund-admin", baseUrl))
                .withPropertyValues(group("preorder", baseUrl))
                .withPropertyValues(group("catalog", baseUrl))
                .withPropertyValues(group("member", baseUrl));
    }

    private static String[] group(String name, String baseUrl) {
        String prefix = "spring.http.serviceclient." + name;
        return new String[]{prefix + ".base-url=" + baseUrl, prefix + ".connect-timeout=300ms",
                prefix + ".read-timeout=" + READ_TIMEOUT_MILLIS + "ms"};
    }

    /*
     * 클래스패스에는 AWS SDK 가 끌어온 Apache HttpClient 5 가 있어, 고정이 없으면 자동설정이 그것을 고른다 — 그 기본 풀
     * (주소당 5개 · 대기 최대 3분)이 결제 승인(읽기 70초) 몇 건에 막힌다. 빈 종류만이 아니라 세 그룹의 실제 호출이 JDK 로
     * 나가는지 본다 — 읽기 기한 초과의 원인이 JDK 의 HttpTimeoutException 이다(Apache 면 SocketTimeoutException).
     */
    @Test
    void everyGroupCallsThroughJdk() {
        runner("http://127.0.0.1:" + server.getAddress().getPort()).run(context -> {
            assertThat(context.getBean(ClientHttpRequestFactoryBuilder.class))
                    .isInstanceOf(JdkClientHttpRequestFactoryBuilder.class);
            assertThat(rootCause(catchThrowable(() -> context.getBean(PaymentConfirmClient.class)
                    .confirm(PROVIDER_ORDER_ID, ConfirmRequest.of(PayableTarget.of(ORDER), "tgen_wiring", true), null))))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(rootCause(catchThrowable(() -> context.getBean(PaymentClient.class)
                    .openCapture(CaptureRequest.of(PayableTarget.of(ORDER)), null))))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(rootCause(catchThrowable(() -> context.getBean(PaymentDeadLetterClient.class)
                    .redrive(UUID.randomUUID(), null))))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(rootCause(catchThrowable(() -> context.getBean(PreorderClient.class)
                    .getPayability("9f1c2d3e-0000-4000-8000-000000000001", null))))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(rootCause(catchThrowable(() -> context.getBean(MemberClient.class).getNames(List.of(UUID.randomUUID())))))
                    .isInstanceOf(HttpTimeoutException.class);
        });
    }

    // 회원 이름(member)은 메서드 인자가 아니라 지금 요청한 관리자의 토큰을 인터셉터로 싣는다 — member 는 ADMIN 토큰만 받는다
    @Test
    void memberGroupRelaysTheRequestToken() throws IOException {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer member = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        member.createContext("/", exchange -> {
            received.add(exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"success\":true,\"data\":{\"items\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        member.start();
        try {
            runner("http://127.0.0.1:" + member.getAddress().getPort()).run(context -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new NovaAuthentication(new AuthenticatedPrincipal("admin", Role.ADMIN), "admin-token"));
                try {
                    context.getBean(MemberClient.class).getNames(List.of(UUID.randomUUID()));
                    context.getBean(CatalogClient.class).getOptions(List.of(UUID.randomUUID()), null);
                } catch (RuntimeException ignored) {
                    // catalog 응답 모양은 보지 않는다 — 헤더만 본다
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        } finally {
            member.stop(0);
        }

        assertThat(received).containsExactly("/internal/customers/names Bearer admin-token", "/internal/options null");
    }

    // 승인 그룹의 읽기 기한이 설정에서 온다. 넘으면 실패가 아니라 결과 모름(주문은 승인 중으로 둔다)
    @Test
    void confirmReadTimeoutIsPending() {
        runner("http://127.0.0.1:" + server.getAddress().getPort()).run(context -> {
            long started = System.nanoTime();

            PaymentConfirmation result = context.getBean(PaymentConfirmer.class)
                    .confirm(PayableTarget.of(ORDER), PROVIDER_ORDER_ID, "tgen_wiring", "wiring-session", true);

            assertThat(result).isEqualTo(new PaymentConfirmation.Pending());
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(STALL_MILLIS);
        });
    }

    // 연결을 맺지 못했다 — 이번 요청은 나가지 않았지만 앞선 요청이 시작했을 수 있어 주문은 그대로 두고 503 으로 답한다
    @Test
    void refusedConnectionIsUnanswered() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        runner("http://127.0.0.1:" + closedPort).run(context ->
                assertThat(context.getBean(PaymentConfirmer.class)
                        .confirm(PayableTarget.of(ORDER), PROVIDER_ORDER_ID, "tgen_wiring", "wiring-session", true))
                        .isInstanceOf(PaymentConfirmation.Unanswered.class));
    }

    // 지표(http.client.requests)의 uri 태그는 경로만 — 목록 파라미터를 원소마다 펼친 템플릿이면 묻는 옵션 수마다 다른 값이 된다.
    // 쿼리 없는 경로 변수 템플릿은 그대로 남고, 지표 이름 설정도 그대로 먹는다
    @Test
    void uriTagIsPathOnly() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        List<String> uriTags = new CopyOnWriteArrayList<>();
        ObservationHandler<ClientRequestObservationContext> recorder = new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ClientRequestObservationContext;
            }

            @Override
            public void onStop(ClientRequestObservationContext context) {
                uriTags.add(context.getName() + " " + context.getLowCardinalityKeyValue("uri").getValue());
            }
        };
        runner("http://127.0.0.1:" + closedPort)
                .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                        RestClientObservationAutoConfiguration.class))
                .withBean(ObservationHandler.class, () -> recorder)
                .withPropertyValues("management.observations.http.client.requests.name=internal.calls")
                .run(context -> {
                    CatalogClient client = context.getBean(CatalogClient.class);
                    catchThrowable(() -> client.getOptions(List.of(UUID.randomUUID()), null));
                    catchThrowable(() -> client.getOptions(IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList(), null));
                    catchThrowable(() -> context.getBean(PreorderClient.class).getPayability("9f1c2d3e-0000-4000-8000-000000000001", null));

                    assertThat(uriTags).containsExactly("internal.calls /internal/options", "internal.calls /internal/options",
                            "internal.calls /internal/preorders/{preorderId}/payability");
                });
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
