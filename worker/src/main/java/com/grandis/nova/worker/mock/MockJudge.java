package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.mock.MockReply.TransportFailure;
import tools.jackson.databind.JsonNode;

/**
 * mock 응답을 판정한다. HTTP 상태보다 errorCode 를 먼저 본다 — 같은 404 라도 "등록 없음"과 "없는 경로"가 다르다.
 * errorCode 가 없는 응답은 mock 이 아니라 앞단 장비나 다른 서버가 보낸 것이다.
 */
final class MockJudge {

    static final String KEY_CANCELED = "KEY_CANCELED";
    static final String INVALID_REQUEST = "INVALID_REQUEST";
    private static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String ENTRY_COUNT_MISMATCH = "ENTRY_COUNT_MISMATCH";
    private static final String NO_SUCH_ENDPOINT = "NO_SUCH_ENDPOINT";

    private MockJudge() {
    }

    /** 등록. 저장 전 실패가 확실한 것만 일시 실패이고, 요청이 닿았을 수 있으면 결과 모름이다. */
    static Judgement register(MockReply reply) {
        if (!reply.responded()) {
            return reply.failure() == TransportFailure.CONNECT
                    ? Judgement.failed(Verdict.TRANSIENT, reply, true)
                    : Judgement.failed(Verdict.UNKNOWN, reply, false);
        }
        int status = reply.status();
        if (status == 201 && reply.text("externalNumber") != null) {
            return Judgement.success(reply.text("externalNumber"));
        }
        if (status / 100 == 2) {
            // 깨진 201 · 201 이 아닌 2xx — 저장됐을 수 있다
            return Judgement.of(Verdict.UNKNOWN, reply);
        }
        if (status == 429) {
            return Judgement.of(Verdict.TRANSIENT, reply).after(reply.retryAfter());
        }
        String errorCode = reply.errorCode();
        if (errorCode == null) {
            return withoutErrorCode(reply, status == 502 || status == 503 ? Verdict.TRANSIENT : Verdict.UNKNOWN);
        }
        if (errorCode.equals(NO_SUCH_ENDPOINT)) {
            return Judgement.of(Verdict.MISCONFIGURED, reply);
        }
        if (status / 100 == 5) {
            return errorCode.equals(UPSTREAM_UNAVAILABLE)
                    ? Judgement.of(Verdict.TRANSIENT, reply).retryable()
                    : Judgement.of(Verdict.UNKNOWN, reply);
        }
        return Judgement.of(Verdict.REJECTED, reply);
    }

    /** 키 조회. 장애 주입이 없어 실패는 그대로 "이번 차례는 알 수 없음"이다. */
    static Judgement lookup(MockReply reply) {
        if (!reply.responded()) {
            return Judgement.failed(
                    reply.failure() == TransportFailure.CONNECT ? Verdict.TRANSIENT : Verdict.UNKNOWN, reply, false);
        }
        int status = reply.status();
        if (status == 200 && reply.body() != null) {
            String active = activeNumber(reply.body());
            if (active != null) {
                return Judgement.success(active);
            }
            JsonNode marker = reply.body().get("cancelMarkerAt");
            if (marker != null && !marker.isNull()) {
                return Judgement.of(Verdict.REJECTED, reply).as(KEY_CANCELED);
            }
        }
        if (status / 100 == 2) {
            return Judgement.of(Verdict.UNKNOWN, reply);
        }
        if (status == 429) {
            return Judgement.of(Verdict.TRANSIENT, reply).after(reply.retryAfter());
        }
        String errorCode = reply.errorCode();
        if (errorCode == null) {
            return withoutErrorCode(reply, Verdict.UNKNOWN);
        }
        if (errorCode.equals(NO_SUCH_ENDPOINT)) {
            return Judgement.of(Verdict.MISCONFIGURED, reply);
        }
        if (status == 404 && errorCode.equals(NOT_FOUND)) {
            return Judgement.of(Verdict.ABSENT, reply);
        }
        return Judgement.of(status / 100 == 5 ? Verdict.UNKNOWN : Verdict.REJECTED, reply);
    }

    /** 취소. 여러 번 보내도 결과가 같아서 실패는 모두 일시 실패다 — 조회 없이 다시 보낸다. */
    static Judgement cancel(MockReply reply) {
        if (!reply.responded()) {
            return Judgement.failed(Verdict.TRANSIENT, reply, true);
        }
        int status = reply.status();
        if (status == 200) {
            JsonNode numbers = reply.body() == null ? null : reply.body().get("externalNumbers");
            JsonNode first = numbers != null && numbers.isArray() && !numbers.isEmpty() ? numbers.get(0) : null;
            return Judgement.success(first != null && first.isString() ? first.stringValue() : null);
        }
        if (status == 429) {
            return Judgement.of(Verdict.TRANSIENT, reply).after(reply.retryAfter());
        }
        if (status / 100 == 2) {
            return Judgement.of(Verdict.TRANSIENT, reply);
        }
        String errorCode = reply.errorCode();
        if (errorCode == null) {
            return withoutErrorCode(reply, Verdict.TRANSIENT);
        }
        if (errorCode.equals(NO_SUCH_ENDPOINT)) {
            return Judgement.of(Verdict.MISCONFIGURED, reply);
        }
        return status / 100 == 5
                ? Judgement.of(Verdict.TRANSIENT, reply).retryable()
                : Judgement.of(Verdict.REJECTED, reply);
    }

    /** 드로우 응모. 같은 키로 다시 보내면 처음 결과가 와서(재생) 결과를 몰라도 조회 없이 다시 보낸다. */
    static Judgement drawEntry(MockReply reply) {
        if (reply.responded() && reply.status() == 201 && reply.errorCode() == null) {
            return Judgement.success(null);
        }
        return replayable(reply);
    }

    /** 드로우 회차 조회. 200 이면 받은 응모 수(entryCount)가 있어야 한다. 404 NOT_FOUND 는 받은 응모가 아직 없다는 뜻이다. */
    static Judgement drawStatus(MockReply reply) {
        if (reply.responded() && reply.status() == 200) {
            JsonNode count = reply.body() == null ? null : reply.body().get("entryCount");
            return count != null && count.isIntegralNumber()
                    ? Judgement.success(null) : Judgement.of(Verdict.UNKNOWN, reply);
        }
        if (reply.responded() && reply.status() == 404 && NOT_FOUND.equals(reply.errorCode())) {
            return Judgement.of(Verdict.ABSENT, reply);
        }
        return replayable(reply);
    }

    /**
     * 추첨. 2xx 면(Mock 은 200) 당첨 응모 목록(winnerEntryIds)이 있어야 한다. 받은 수가 기대와 다르거나(409 ENTRY_COUNT_MISMATCH) 회차에
     * 응모가 없으면(404 NOT_FOUND) 거절이 아니라 기다림이다 — 늦은 응모가 아직 오는 중일 수 있다.
     */
    static Judgement draw(MockReply reply) {
        if (reply.responded() && reply.status() / 100 == 2) {
            JsonNode winners = reply.body() == null ? null : reply.body().get("winnerEntryIds");
            return winners != null && winners.isArray()
                    ? Judgement.success(null) : Judgement.of(Verdict.UNKNOWN, reply);
        }
        if (reply.responded() && (reply.status() == 409 && ENTRY_COUNT_MISMATCH.equals(reply.errorCode())
                || reply.status() == 404 && NOT_FOUND.equals(reply.errorCode()))) {
            return Judgement.of(Verdict.PENDING, reply);
        }
        return replayable(reply);
    }

    /**
     * 다시 보내도 처음 결과가 오는 호출(드로우)에서 성공 · 기다림이 아닌 응답. 2xx 인데 모양이 다르면 결과 모름(계약 확인 경고),
     * 408 · 429 · 5xx 는 일시 실패, 없는 경로 · errorCode 없는 3xx · 4xx 는 설정 오류, 그 밖의 4xx 는 거절이다.
     */
    private static Judgement replayable(MockReply reply) {
        if (!reply.responded()) {
            return Judgement.failed(Verdict.TRANSIENT, reply, true);
        }
        int status = reply.status();
        if (status / 100 == 2) {
            return Judgement.of(Verdict.UNKNOWN, reply);
        }
        if (status == 408 || status == 429) {
            return Judgement.of(Verdict.TRANSIENT, reply).after(reply.retryAfter());
        }
        String errorCode = reply.errorCode();
        if (errorCode == null) {
            return withoutErrorCode(reply, Verdict.TRANSIENT);
        }
        if (errorCode.equals(NO_SUCH_ENDPOINT)) {
            return Judgement.of(Verdict.MISCONFIGURED, reply);
        }
        return status / 100 == 5
                ? Judgement.of(Verdict.TRANSIENT, reply).retryable()
                : Judgement.of(Verdict.REJECTED, reply);
    }

    /**
     * errorCode 가 없는 응답. 5xx 는 앞단 장비가 대신 보낸 것이라 호출마다 뜻이 다르고(serverError), 그 밖(3xx · 4xx)은
     * mock 이 아닌 곳에 닿은 것이다. 503 의 Retry-After 는 따르고, 그때는 곧바로 다시 보내지 않는다.
     */
    private static Judgement withoutErrorCode(MockReply reply, Verdict serverError) {
        if (reply.status() / 100 != 5) {
            return Judgement.of(Verdict.MISCONFIGURED, reply);
        }
        Judgement judgement = Judgement.of(serverError, reply);
        if (serverError != Verdict.TRANSIENT) {
            return judgement;
        }
        return reply.retryAfter() == null ? judgement.retryable() : judgement.after(reply.retryAfter());
    }

    private static String activeNumber(JsonNode body) {
        JsonNode registrations = body.get("registrations");
        if (registrations == null || !registrations.isArray()) {
            return null;
        }
        for (JsonNode registration : registrations) {
            JsonNode status = registration.get("status");
            JsonNode number = registration.get("externalNumber");
            if (status != null && "ACTIVE".equals(status.stringValue(null)) && number != null && number.isString()) {
                return number.stringValue();
            }
        }
        return null;
    }
}
