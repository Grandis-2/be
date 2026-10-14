package com.grandis.nova.worker.mock;

import com.grandis.nova.worker.job.AttemptResult;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

/**
 * mock 호출 한 번의 결과. 호출하지 않았으면 verdict 가 NOT_CALLED 이고 skipped 에 이유가 있다.
 *
 * @param httpStatus 응답을 받지 못했거나 호출하지 않았으면 null
 * @param retryNow   같은 차례 안에서 곧바로 다시 보내도 되는 일시 실패다
 * @param retryAfter 외부가 이만큼 뒤에 다시 보내라고 했다. 없으면 null
 * @param body       성공한 응답의 본문(드로우 회차 조회 · 추첨이 읽는다). 성공이 아니면 null
 */
public record MockResult(
        Verdict verdict,
        Skipped skipped,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        String externalNumber,
        boolean retryNow,
        Duration retryAfter,
        Instant startedAt,
        Instant finishedAt,
        JsonNode body
) {

    public enum Skipped {
        CIRCUIT_OPEN,
        RATE_LIMITED
    }

    /** 시도 원장의 오류 메시지 칸 길이. 외부가 준 문구가 길면 여기까지만 싣는다. */
    private static final int MAX_MESSAGE = 500;

    static MockResult notCalled(Skipped reason, Instant now) {
        return new MockResult(Verdict.NOT_CALLED, reason, null, null, null, null, false, null, now, now, null);
    }

    static MockResult of(Judgement judgement, MockReply reply, Instant startedAt, Instant finishedAt) {
        String message = judgement.errorMessage();
        return new MockResult(judgement.verdict(), null, reply.responded() ? reply.status() : null,
                judgement.errorCode(),
                message != null && message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message,
                judgement.externalNumber(), judgement.retryNow(), judgement.retryAfter(), startedAt, finishedAt,
                judgement.verdict() == Verdict.SUCCESS ? reply.body() : null);
    }

    public Duration elapsed() {
        return Duration.between(startedAt, finishedAt);
    }

    /** 시도 원장에 남길 결과. 시도로 세지 않는 것(설정 오류 · 호출 안 함 · 조회의 등록 없음 · 드로우의 기다림)은 null. */
    public AttemptResult attemptResult() {
        return switch (verdict) {
            case SUCCESS -> AttemptResult.SUCCESS;
            case TRANSIENT -> AttemptResult.TRANSIENT_FAILURE;
            case UNKNOWN -> AttemptResult.UNKNOWN;
            case REJECTED -> AttemptResult.REJECTED;
            case MISCONFIGURED, ABSENT, PENDING, NOT_CALLED -> null;
        };
    }
}
