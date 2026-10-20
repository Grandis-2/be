package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.mock.FakeMock.Received;
import com.grandis.nova.worker.mock.MockReply.TransportFailure;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 가짜 mock 에 실제 HTTP 로 보낸다. 보내는 모양과, 응답 · 실패를 있는 그대로 건네는지를 본다. */
class MockClientTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNode REGISTER = JSON.readTree("""
            {"ourReservationId":"K-1","customerRef":"c","itemCode":"i","optionCode":"o","qty":1,"scope":"preorder"}""");

    FakeMock mock;
    CloseableHttpClient http;
    MockClient client;

    @BeforeEach
    void setUp() {
        mock = new FakeMock();
        client = clientFor(mock.baseUrl());
    }

    @AfterEach
    void tearDown() throws Exception {
        http.close();
        mock.close();
    }

    @Test
    void 등록은_멱등_키와_JSON_본문을_보낸다() {
        mock.respond(FakeMock.json(201, "{\"externalNumber\":\"R-1\"}"));

        MockReply reply = client.register("K-1", REGISTER);

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.text("externalNumber")).isEqualTo("R-1");
        Received sent = mock.requests().getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/external/reservations");
        assertThat(sent.idempotencyKey()).isEqualTo("K-1");
        assertThat(sent.contentType()).startsWith("application/json");
        assertThat(sent.accept()).isEqualTo("application/json");
        assertThat(sent.acceptEncoding()).as("압축을 요청하지 않는다").isNull();
        assertThat(JSON.readTree(sent.body())).isEqualTo(REGISTER);
    }

    @Test
    void 조회와_취소는_정해진_경로로_보낸다() {
        mock.respond(FakeMock.json(200, "{}"));

        client.lookup("K-1");
        client.cancel(JSON.readTree("{\"externalKey\":\"K-1\",\"reason\":\"USER_CANCEL\"}"));

        assertThat(mock.requests()).extracting(Received::method, Received::path).containsExactly(
                tuple("GET", "/external/reservations/by-key/K-1"),
                tuple("POST", "/external/cancellations"));
        assertThat(mock.requests().get(1).idempotencyKey()).isNull();
    }

    @Test
    void 드로우는_응모_id_를_멱등_키로_보내고_추첨은_회차_경로로만_보낸다() {
        mock.respond(FakeMock.json(200, "{}"));
        JsonNode entry = JSON.readTree("{\"entryId\":\"E-1\",\"drawId\":\"D-1\",\"customerId\":\"C-1\"}");
        JsonNode draw = JSON.readTree("{\"winnerCount\":2,\"expectedEntryCount\":5}");

        client.enterDraw("E-1", entry);
        client.drawStatus("D-1");
        client.draw("D-1", draw);

        assertThat(mock.requests()).extracting(Received::method, Received::path, Received::idempotencyKey)
                .containsExactly(
                        tuple("POST", "/external/draw-entries", "E-1"),
                        tuple("GET", "/external/draws/D-1", null),
                        tuple("POST", "/external/draws/D-1/draw", null));
        assertThat(JSON.readTree(mock.requests().get(0).body())).isEqualTo(entry);
        assertThat(JSON.readTree(mock.requests().get(2).body())).isEqualTo(draw);
        assertThat(mock.requests().get(2).contentType()).startsWith("application/json");
    }

    @Test
    void 오류_응답도_던지지_않고_상태와_errorCode_를_건넨다() {
        mock.respond(FakeMock.json(409, "{\"errorCode\":\"KEY_CANCELED\",\"errorMessage\":\"취소된 키\"}"));

        MockReply reply = client.register("K-1", REGISTER);

        assertThat(reply.responded()).isTrue();
        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.errorCode()).isEqualTo("KEY_CANCELED");
    }

    @Test
    void JSON_객체가_아닌_본문과_빈_본문은_본문_없음으로_건넨다() {
        mock.respond(FakeMock.html(502));
        assertThat(client.register("K-1", REGISTER)).extracting(MockReply::status, MockReply::body)
                .containsExactly(502, null);

        mock.respond(FakeMock.empty(500));
        assertThat(client.register("K-1", REGISTER)).extracting(MockReply::status, MockReply::body)
                .containsExactly(500, null);

        mock.respond(FakeMock.json(201, "[1,2]"));
        assertThat(client.register("K-1", REGISTER).body()).isNull();
    }

    @Test
    void Retry_After_를_읽고_읽을_수_없으면_없는_것으로_본다() {
        mock.respond(FakeMock.header("Retry-After", "7", FakeMock.html(503)));
        assertThat(client.register("K-1", REGISTER).retryAfter()).isEqualTo(Duration.ofSeconds(7));

        mock.respond(FakeMock.header("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT", FakeMock.html(503)));
        assertThat(client.register("K-1", REGISTER).retryAfter()).isNull();
    }

    @Test
    void 본문은_상한까지만_읽는다() {
        mock.respond(FakeMock.json(500, "{\"errorCode\":\"UPSTREAM_UNAVAILABLE\",\"errorMessage\":\"" + "x".repeat(4096)
                + "\"}"));

        MockReply reply = client.register("K-1", REGISTER);

        assertThat(reply.status()).isEqualTo(500);
        assertThat(reply.body()).as("잘린 본문은 JSON 으로 읽히지 않는다").isNull();
    }

    /** 다시 보내기는 worker 가 정한다. 클라이언트가 몰래 다시 보내면 횟수 · 차단기 집계가 어긋난다. */
    @Test
    void 실패해도_스스로_다시_보내지_않고_리다이렉트를_따라가지_않는다() {
        mock.respond(FakeMock.html(503));
        client.lookup("K-1");
        assertThat(mock.requests()).hasSize(1);

        mock.respond(FakeMock.header("Location", mock.baseUrl() + "/elsewhere", FakeMock.empty(302)));
        MockReply redirected = client.lookup("K-1");
        assertThat(redirected.status()).isEqualTo(302);
        assertThat(mock.requests()).hasSize(2);
    }

    @Test
    void 연결하지_못하면_연결_실패다() throws Exception {
        URI closed = mock.baseUrl();
        mock.close();
        http.close();
        client = clientFor(closed);

        MockReply reply = client.register("K-1", REGISTER);

        assertThat(reply.responded()).isFalse();
        assertThat(reply.failure()).isEqualTo(TransportFailure.CONNECT);
    }

    /** 요청이 가지 않은 것이 확실한 실패만 연결 실패다. 연결 시간 초과는 응답 시간 초과의 하위 형이라 헷갈리기 쉽다. */
    @Test
    void 실패의_원인으로_요청이_갔는지를_가른다() {
        assertThat(failure(new UnknownHostException("mock.nova.local"))).isEqualTo(TransportFailure.CONNECT);
        assertThat(failure(new ConnectException("refused"))).isEqualTo(TransportFailure.CONNECT);
        assertThat(failure(new ConnectTimeoutException("connect"))).isEqualTo(TransportFailure.CONNECT);
        assertThat(failure(new ConnectionRequestTimeoutException("pool"))).isEqualTo(TransportFailure.CONNECT);
        assertThat(failure(new SocketTimeoutException("read"))).isEqualTo(TransportFailure.READ_TIMEOUT);
        assertThat(failure(new IOException("reset"))).isEqualTo(TransportFailure.BROKEN);
    }

    private static TransportFailure failure(IOException cause) {
        return MockClient.failureOf(new ResourceAccessException("I/O error", cause));
    }

    @Test
    void 응답이_제한_시간_안에_오지_않으면_응답_시간_초과다() {
        mock.respond(FakeMock.after(Duration.ofSeconds(1), FakeMock.json(201, "{\"externalNumber\":\"R-1\"}")));

        assertThat(client.register("K-1", REGISTER).failure()).isEqualTo(TransportFailure.READ_TIMEOUT);
    }

    @Test
    void 응답_없이_끊기면_끊김이다() {
        mock.respond(FakeMock.drop());

        assertThat(client.register("K-1", REGISTER).failure()).isEqualTo(TransportFailure.BROKEN);
        assertThat(mock.requests()).as("끊겨도 다시 보내지 않는다").hasSize(1);
    }

    private MockClient clientFor(URI baseUrl) {
        http = MockConfig.httpClient(Settings.mock(baseUrl), 4);
        return new MockClient(MockConfig.restClient(http, Settings.mock(baseUrl)), JSON, 1024);
    }
}
