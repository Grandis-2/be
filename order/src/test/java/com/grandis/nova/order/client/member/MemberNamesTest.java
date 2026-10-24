package com.grandis.nova.order.client.member;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 관리자 화면의 회원 이름 — 계약(contracts/member-internal.md 의 GET /internal/customers/names)대로 묻고, 한 화면에 많아야 한 번 부르며,
 * 실패해도 예외 없이 이름만 비우는지. 아래 JSON 은 member AdminMembersResponse 를 ApiResponse 로 감싼 모양을 복제한 것이다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MemberNamesTest {

    static final UUID KIM = UUID.fromString("0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c01");
    static final UUID LEE = UUID.fromString("0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c02");
    static final UUID GONE = UUID.fromString("0199a3f2-7c4e-7a10-8b2d-3f4e5a6b7c03");

    MockRestServiceServer server;
    CircuitBreakerRegistry circuitBreakers;
    MemberNames names;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://member");
        server = MockRestServiceServer.bindTo(builder).build();
        MemberClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(MemberClient.class);
        circuitBreakers = CircuitBreakerRegistry.ofDefaults();
        names = new MemberNames(client, circuitBreakers,
                BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()));
    }

    @Test
    @DisplayName("같은 id 는 한 번만 ids 로 보내고 items 를 회원 id 로 읽는다 — 없는 회원은 빠지고, 모르는 칸은 무시한다")
    void sendsDistinctIdsAndReadsItems() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://member/internal/customers/names?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("ids", KIM.toString(), LEE.toString(), GONE.toString()))
                .andRespond(withSuccess(body(KIM, "김**", LEE, "이**"), MediaType.APPLICATION_JSON));

        assertThat(names.find(List.of(KIM, LEE, KIM, GONE))).containsOnly(
                org.assertj.core.api.Assertions.entry(KIM, "김**"), org.assertj.core.api.Assertions.entry(LEE, "이**"));
        server.verify();
    }

    @Test
    @DisplayName("받은 이름은 캐시한다 — 없는 회원도 '없음' 으로 남겨, 다음 화면은 캐시에 없는 id 만 묻는다")
    void cachesNamesAndAbsences() {
        server.expect(ExpectedCount.once(), queryParam("ids", KIM.toString(), GONE.toString()))
                .andRespond(withSuccess(body(KIM, "김**"), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), queryParam("ids", LEE.toString()))
                .andRespond(withSuccess(body(LEE, "이**"), MediaType.APPLICATION_JSON));

        names.find(List.of(KIM, GONE));
        assertThat(names.find(List.of(KIM, GONE, LEE))).containsOnlyKeys(KIM, LEE);
        assertThat(names.find(List.of(KIM, GONE, LEE))).as("세 번째는 부르지 않는다").containsOnlyKeys(KIM, LEE);
        server.verify();
    }

    @Test
    @DisplayName("5xx · 4xx · 읽을 수 없는 응답이면 예외 없이 캐시에 있는 이름만 준다 — 실패는 캐시하지 않아 다음 화면이 다시 묻는다")
    void failuresLeaveNamesEmpty() {
        server.expect(queryParam("ids", KIM.toString())).andRespond(withSuccess(body(KIM, "김**"), MediaType.APPLICATION_JSON));
        server.expect(queryParam("ids", LEE.toString())).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(queryParam("ids", LEE.toString())).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(queryParam("ids", LEE.toString())).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));
        server.expect(queryParam("ids", LEE.toString())).andRespond(withSuccess(body(LEE, "이**"), MediaType.APPLICATION_JSON));

        names.find(List.of(KIM));
        assertThat(names.find(List.of(KIM, LEE))).containsOnlyKeys(KIM);
        assertThat(names.find(List.of(KIM, LEE))).containsOnlyKeys(KIM);
        assertThat(names.find(List.of(KIM, LEE))).containsOnlyKeys(KIM);
        assertThat(names.find(List.of(KIM, LEE))).containsOnlyKeys(KIM, LEE);
        server.verify();
    }

    @Test
    @DisplayName("실패 로그에 요청 URL(ids)을 싣지 않고 종류 · 상태만 남긴다. 계약과 다른 응답(우리 쪽 결함)은 스택과 함께 남긴다")
    void failureLogsCarryNoRequestUrl(CapturedOutput output) {
        server.expect(queryParam("ids", KIM.toString())).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(queryParam("ids", KIM.toString())).andRespond(request -> {
            throw new java.net.SocketTimeoutException("read timed out");
        });
        server.expect(queryParam("ids", KIM.toString())).andRespond(request -> {
            throw new IllegalStateException("우리 쪽 결함 자리");
        });

        names.find(List.of(KIM));
        names.find(List.of(KIM));
        names.find(List.of(KIM));

        assertThat(output).contains("ServiceUnavailable 503", "ResourceAccessException cause=SocketTimeoutException")
                .contains("예상하지 못한 오류").contains("IllegalStateException: 우리 쪽 결함 자리").contains("\tat ")
                .doesNotContain(KIM.toString()).doesNotContain("http://member");
        server.verify();
    }

    @Test
    @DisplayName("회로가 열려 있으면 부르지 않는다 — member 장애 동안 목록이 시간 초과를 기다리지 않는다")
    void openCircuitDoesNotCall() {
        circuitBreakers.circuitBreaker(MemberNames.DEPENDENCY).transitionToOpenState();

        assertThat(names.find(List.of(KIM))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("물은 적 없는 회원 · 이름이 빈 줄은 쓰지 않는다")
    void ignoresUnaskedAndEmptyItems() {
        server.expect(queryParam("ids", KIM.toString())).andRespond(withSuccess("""
                {"success":true,"data":{"items":[{"memberId":"%s","name":"이**"},{"memberId":"%s","name":null}]}}
                """.formatted(LEE, KIM), MediaType.APPLICATION_JSON));

        assertThat(names.find(List.of(KIM))).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("빈 목록은 부르지 않고, 100개가 넘으면 100개씩 나눠 묻는다(member 의 한 번 상한)")
    void emptyAndChunked() {
        assertThat(names.find(List.of())).isEmpty();

        List<UUID> many = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();
        server.expect(queryParam("ids", many.subList(0, 100).stream().map(UUID::toString).toArray(String[]::new)))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        server.expect(queryParam("ids", many.get(100).toString()))
                .andRespond(withSuccess(body(many.get(100), "박**"), MediaType.APPLICATION_JSON));

        assertThat(names.find(many)).containsOnlyKeys(many.get(100));
        server.verify();
    }

    /** id · 이름 쌍들 → member 응답 본문. */
    private static String body(Object... idAndNames) {
        String items = IntStream.range(0, idAndNames.length / 2)
                .mapToObj(i -> "{\"memberId\":\"%s\",\"name\":\"%s\",\"addedLater\":1}".formatted(idAndNames[2 * i], idAndNames[2 * i + 1]))
                .collect(Collectors.joining(","));
        return "{\"success\":true,\"data\":{\"items\":[" + items + "]},\"error\":null,\"timestamp\":\"2026-10-24T06:00:00Z\",\"traceId\":\"t-1\"}";
    }
}
