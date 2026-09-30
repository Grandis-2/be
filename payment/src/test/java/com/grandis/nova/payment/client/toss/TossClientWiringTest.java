package com.grandis.nova.payment.client.toss;

import com.grandis.nova.payment.config.HttpClientConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 스프링 설정으로 조립된 토스 클라이언트가 실제 HTTP 로 설정대로 나가는지 — spring.http.serviceclient.toss(주소 · 타임아웃, D9)와
 * 그룹 한정 Basic 인증, HTTP 구현 고정(JDK). MockRestServiceServer 는 요청 팩토리를 바꿔 끼우므로 타임아웃 · 구현을 볼 수 없어
 * 로컬 HTTP 서버를 쓴다.
 */
@ExtendWith(OutputCaptureExtension.class)
class TossClientWiringTest {

    // 고정이 없으면 자동설정이 SimpleClientHttpRequestFactory(HttpURLConnection)를 고르게 하는 설정
    static final String AUTO_CONFIGURATION_PICKS_ANOTHER = "spring.http.clients.imperative.factory=simple";

    // 시간 기반 테스트의 여유. 정상 응답은 기한보다 한참 빠르고(로컬), 멈춤은 기한의 4배라 CI 부하에도 결과가 뒤집히지 않는다
    static final long READ_TIMEOUT_MILLIS = 1_000;
    static final long STALL_MILLIS = 4_000;

    HttpServer server;
    final List<Recorded> received = new CopyOnWriteArrayList<>();
    volatile long delayMillis;
    // 헤더와 본문 앞부분을 보낸 뒤 멈춘다 — 본문 수신 중 기한이 지나는 경우
    volatile boolean stallMidBody;
    // 302 로 다른 곳을 가리킨다
    volatile boolean redirect;

    record Recorded(String method, String path, String authorization, String idempotencyKey) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
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
                .withUserConfiguration(HttpClientConfig.class)
                .withBean(TossPaymentClient.class)
                .withPropertyValues(
                        "nova.payment.toss.secret-key=" + TossTestClients.MERCHANT_CREDENTIAL,
                        "spring.http.serviceclient.toss.base-url=" + baseUrl,
                        "spring.http.serviceclient.toss.connect-timeout=3s",
                        "spring.http.serviceclient.toss.read-timeout=" + READ_TIMEOUT_MILLIS + "ms",
                        "spring.http.serviceclient.toss.redirects=dont-follow");
    }

    @Test
    void tossGroupSendsBasicAuthorizationToConfiguredBaseUrl() {
        runner().run(context -> {
            TossCommandResult result = context.getBean(TossPaymentClient.class)
                    .confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

            assertThat(result).isInstanceOf(TossCommandResult.Succeeded.class);
            assertThat(received).containsExactly(new Recorded("POST", "/v1/payments/confirm",
                    "Basic " + Base64.getEncoder().encodeToString(
                            (TossTestClients.MERCHANT_CREDENTIAL + ":").getBytes(StandardCharsets.UTF_8)),
                    TossStubs.DEDUP));
        });
    }

    // 시크릿 키는 토스 그룹에만 실린다 — 같은 서비스의 다른 RestClient(자동설정 빌더로 만드는 것)에는 실리지 않는다.
    @Test
    void otherClientsDoNotCarryTossCredential() {
        runner().run(context -> {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            context.getBean(RestClient.Builder.class).baseUrl(baseUrl).build()
                    .get().uri("/other").retrieve().toBodilessEntity();

            assertThat(received).singleElement()
                    .satisfies(recorded -> assertThat(recorded.authorization()).isNull());
        });
    }

    /*
     * HTTP 구현은 JDK 로 고정한다(HttpClientConfig). 클래스패스를 보고 고르게 두면 딸려 온 의존(AWS SDK 의 apache5-client 등)이
     * 구현을 바꾸고, 그 기본 풀 대기(최대 3분)가 시간 예산(연결 3s + 읽기 60s ≤ 리스 70s)을 깬다.
     * 지금 클래스패스에는 JDK 말고 고를 것이 없어 고정이 없어도 JDK 가 된다 — 그래서 자동설정이 다른 구현(simple)을 고르게 해 두고,
     * 그래도 JDK 인지 본다. 빈 종류와, 실제 토스 호출이 그 구현으로 나갔는지(타임아웃 원인이 JDK 의 HttpTimeoutException)를 함께 본다.
     */
    @Test
    void httpImplementationIsPinnedToJdkEvenWhenAutoConfigurationWouldPickAnother(CapturedOutput output) {
        delayMillis = STALL_MILLIS;
        runner().withPropertyValues(AUTO_CONFIGURATION_PICKS_ANOTHER).run(context -> {
            assertThat(context.getBean(ClientHttpRequestFactoryBuilder.class))
                    .isInstanceOf(JdkClientHttpRequestFactoryBuilder.class);

            context.getBean(TossPaymentClient.class).confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

            assertThat(output.getAll()).contains("cause=java.net.http.HttpTimeoutException");
        });
    }

    // 읽기 타임아웃이 설정에서 온다(운영 60s, 여기서는 1s). 응답이 늦으면 실패가 아니라 결과 불명이고, 같은 키 재전송으로 푼다.
    @Test
    void readTimeoutFromConfigurationIsUnknown(CapturedOutput output) {
        delayMillis = STALL_MILLIS;
        runner().run(context -> {
            TossCommandResult result = context.getBean(TossPaymentClient.class)
                    .confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

            assertThat(result).isEqualTo(new TossCommandResult.Unknown(UnknownReason.TIMEOUT, null));
            assertThat(((TossCommandResult.Unknown) result).resolution()).isEqualTo(UnknownReason.Resolution.RESEND);
            // JDK 구현의 읽기 타임아웃(응답 헤더까지의 요청 제한 시간)이 분류에 걸리는지 — 다른 구현이면 원인 예외가 다르다
            assertThat(output.getAll()).contains("cause=java.net.http.HttpTimeoutException");
        });
    }

    /*
     * 기한은 응답 헤더까지가 아니라 본문을 다 읽을 때까지다(Spring 7 JdkClientHttpRequest 의 TimeoutHandler). 헤더를 받았다는 것은
     * 토스가 처리했다는 뜻이라, 본문 중간에 기한이 지나면 재전송(캐시된 같은 응답)이 아니라 조회로 풀어야 한다.
     */
    @Test
    void deadlineDuringBodyIsUnreadableResolvedByLookup(CapturedOutput output) {
        stallMidBody = true;
        runner().run(context -> {
            TossCommandResult result = context.getBean(TossPaymentClient.class)
                    .confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

            assertThat(result).isEqualTo(new TossCommandResult.Unknown(UnknownReason.UNREADABLE_RESPONSE, null));
            assertThat(((TossCommandResult.Unknown) result).resolution()).isEqualTo(UnknownReason.Resolution.LOOKUP);
            // 네트워크 문제이지 계약 위반이 아니다 — WARN, "읽을 수 없음"(ERROR) 아님
            assertThat(output.getAll()).contains("응답 본문 수신 실패").doesNotContain("2xx 응답을 읽을 수 없음");
        });
    }

    // 리다이렉트를 따라가지 않는다 — 따라가면 POST 가 GET 으로 바뀌어 다른 곳을 부른다. 받은 3xx 는 토스가 처리했는지 모르는 응답이다.
    @Test
    void redirectIsNotFollowed(CapturedOutput output) {
        redirect = true;
        runner().run(context -> {
            TossCommandResult result = context.getBean(TossPaymentClient.class)
                    .confirm(TossStubs.confirmRequest(), TossStubs.idempotencyKey());

            assertThat(received).singleElement().satisfies(r -> assertThat(r.path()).isEqualTo("/v1/payments/confirm"));
            assertThat(result).isEqualTo(new TossCommandResult.Unknown(UnknownReason.UNREADABLE_RESPONSE, null));
            assertThat(output.getAll()).contains("리다이렉트 응답(따라가지 않음) status=302").doesNotContain("빈 2xx");
        });
    }

    // 시크릿 키가 없으면 뜨지 않는다 — 첫 결제 때가 아니라 배포 때 알게.
    @Test
    void missingCredentialFailsStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
                        HttpServiceClientPropertiesAutoConfiguration.class, HttpServiceClientAutoConfiguration.class))
                .withUserConfiguration(HttpClientConfig.class)
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("nova.payment.toss.secret-key"));
    }

    private void respond(HttpExchange exchange) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        exchange.getRequestHeaders().forEach(headers::addAll);
        received.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                headers.getFirst(HttpHeaders.AUTHORIZATION), headers.getFirst(TossIdempotencyKey.HEADER)));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (redirect && !exchange.getRequestURI().getPath().equals("/elsewhere")) {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        byte[] body = TossStubs.payment(TossStubs.PAYMENT_REF, TossStubs.ORDER_REF, "DONE", TossStubs.AMOUNT,
                TossStubs.AMOUNT).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(200, body.length);
            if (stallMidBody) {
                exchange.getResponseBody().write(body, 0, body.length / 2);
                exchange.getResponseBody().flush();
                Thread.sleep(STALL_MILLIS);
                exchange.getResponseBody().write(body, body.length / 2, body.length - body.length / 2);
                return;
            }
            exchange.getResponseBody().write(body);
        } catch (IOException clientGone) {
            // 타임아웃 테스트에서 클라이언트가 먼저 끊는다
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }
}
