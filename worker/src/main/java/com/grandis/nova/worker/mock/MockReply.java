package com.grandis.nova.worker.mock;

import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * mock 이 돌려준 것 그대로. 응답을 받았으면 상태 · 본문이, 받지 못했으면 failure 가 있다.
 *
 * @param body       JSON 객체로 읽힌 본문. 본문이 없거나 JSON 객체가 아니면(HTML 등) null
 * @param retryAfter Retry-After 헤더(초). 없거나 읽을 수 없으면 null
 */
record MockReply(int status, JsonNode body, Duration retryAfter, TransportFailure failure) {

    enum TransportFailure {
        /** 연결하지 못했다(거부 · 연결 시간 초과 · 이름 풀이 실패) — 요청이 가지 않았다. */
        CONNECT,
        /** 응답을 기다리다 시간이 넘었다 — 요청은 갔을 수 있다. */
        READ_TIMEOUT,
        /** 응답 도중 끊겼다 — 요청은 갔을 수 있다. */
        BROKEN
    }

    static MockReply response(int status, JsonNode body, Duration retryAfter) {
        return new MockReply(status, body, retryAfter, null);
    }

    static MockReply failed(TransportFailure failure) {
        return new MockReply(0, null, null, failure);
    }

    boolean responded() {
        return failure == null;
    }

    String errorCode() {
        return text("errorCode");
    }

    /** 본문의 문자열 칸. 본문이 없거나 그 칸이 문자열이 아니면 null. */
    String text(String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
