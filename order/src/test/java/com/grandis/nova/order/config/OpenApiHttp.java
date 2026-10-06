package com.grandis.nova.order.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실서버 문서 시험의 HTTP 호출. JSON 은 200 을 단정한 뒤에만 읽는다 — 오류 봉투를 문서로 읽으면
 * 빈 결과로 실패해 원인이 가려진다. 리다이렉트는 따라가지 않는다(302 를 그대로 본다).
 */
final class OpenApiHttp {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OpenApiHttp() {
    }

    static HttpRequest.Builder request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(int port, String path) throws Exception {
        return send(request(port, path).GET().build());
    }

    static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.uri().getPath()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    static JsonNode getJson(int port, String path) throws Exception {
        return json(get(port, path));
    }
}
