package com.grandis.nova.worker.event;

import com.grandis.nova.worker.job.Actor;
import com.grandis.nova.worker.job.AttemptResult;
import com.grandis.nova.worker.job.JobReady;
import com.grandis.nova.worker.job.JobType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** payload 의 JSON 모양(칸 이름 · 형)이 preorder 와의 계약이다. */
class ResultEventPayloadTest {

    static final UUID JOB = UUID.fromString("0190f3a1-0000-7000-8000-000000000001");
    static final Attempt ATTEMPT = new Attempt(1, 2, 3, Actor.SYSTEM, AttemptResult.TRANSIENT_FAILURE, 503,
            "UPSTREAM_UNAVAILABLE", "down", Instant.parse("2026-10-12T01:00:00Z"), Instant.parse("2026-10-12T01:00:01Z"));

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void 성공_이벤트는_기존_칸에_차례_기록을_더한다() {
        JsonNode payload = json.valueToTree(new ExternalJobSucceeded(JOB, "PO-1", JobType.REGISTER, "EXT-9", ATTEMPT));

        assertThat(payload.get("syncJobId").asString()).isEqualTo(JOB.toString());
        assertThat(payload.get("preorderId").asString()).isEqualTo("PO-1");
        assertThat(payload.get("jobType").asString()).isEqualTo("REGISTER");
        assertThat(payload.get("externalNumber").asString()).isEqualTo("EXT-9");
        assertThat(payload.get("attempt").propertyNames()).containsExactly("run", "generation", "attempt", "actor", "result",
                "httpStatus", "errorCode", "errorMessage", "startedAt", "finishedAt");
    }

    @Test
    void 차례_기록은_세대_차례_결과와_응답을_싣는다() {
        JsonNode attempt = json.valueToTree(new ExternalJobAttempted(JOB, "PO-1", JobType.REGISTER, ATTEMPT)).get("attempt");

        assertThat(attempt.get("generation").asInt()).isEqualTo(2);
        assertThat(attempt.get("attempt").asInt()).isEqualTo(3);
        assertThat(attempt.get("actor").asString()).isEqualTo("SYSTEM");
        assertThat(attempt.get("result").asString()).isEqualTo("TRANSIENT_FAILURE");
        assertThat(attempt.get("httpStatus").asInt()).isEqualTo(503);
        assertThat(attempt.get("startedAt").asString()).isEqualTo("2026-10-12T01:00:00Z");
    }

    @Test
    void 최종_실패는_이유와_마지막_차례를_싣는다() {
        JsonNode payload = json.valueToTree(new ExternalJobFailed(JOB, "PO-1", JobType.REGISTER, "GENERATION_EXHAUSTED",
                ATTEMPT));

        assertThat(payload.get("reason").asString()).isEqualTo("GENERATION_EXHAUSTED");
        assertThat(payload.get("attempt").get("attempt").asInt()).isEqualTo(3);
    }

    /** preorder 는 차례 기록의 이벤트 id 로 이미 받은 이벤트를 가린다 — 없는 이벤트는 만들 수 없게 한다. */
    @Test
    void 실패한_차례와_최종_실패는_차례_기록_없이_만들_수_없다() {
        assertThatThrownBy(() -> new ExternalJobAttempted(JOB, "PO-1", JobType.REGISTER, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExternalJobFailed(JOB, "PO-1", JobType.REGISTER, "PAYLOAD_INVALID", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 작업_메시지는_외부에_보낼_본문을_함께_읽는다() {
        JobReady ready = json.readValue("""
                {"syncJobId":"%s","preorderId":"PO-1","jobType":"CANCEL",
                 "request":{"externalKey":"K-1","reason":"USER"}}""".formatted(JOB), JobReady.class);

        assertThat(ready.syncJobId()).isEqualTo(JOB);
        assertThat(ready.jobType()).isEqualTo(JobType.CANCEL);
        assertThat(ready.request().get("externalKey").asString()).isEqualTo("K-1");
    }
}
