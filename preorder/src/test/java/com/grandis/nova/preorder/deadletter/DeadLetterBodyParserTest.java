package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.preorder.deadletter.application.DeadLetterBodyParser;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterBody;
import com.grandis.nova.preorder.deadletter.domain.FailureReason;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterBodyParserTest {

    final DeadLetterBodyParser parser = new DeadLetterBodyParser(JsonMapper.builder().findAndAddModules().build());

    @Test
    void 받는_종류의_봉투면_검색_칸을_채우고_되돌릴_수_있다() {
        DeadLetterBody body = parser.parse(envelope("EXTERNAL_JOB_SUCCEEDED"));

        assertThat(body.failureReason()).isEqualTo(FailureReason.PROCESSING_FAILED);
        assertThat(body.redrivable()).isTrue();
        assertThat(body.eventId()).isEqualTo("0b6f3c9e-2d41-4c8a-9f7e-5a1d2c3b4e5f");
        assertThat(body.aggregateType()).isEqualTo("PREORDER_SYNC_JOB");
        assertThat(body.aggregateId()).isEqualTo(7L);
        assertThat(body.preorderToken()).isEqualTo("9f1c2d3e");
    }

    @Test
    void 모르는_종류는_되돌려도_같은_결과라_되돌릴_수_없다() {
        DeadLetterBody body = parser.parse(envelope("SOMETHING_NEW"));

        assertThat(body.failureReason()).isEqualTo(FailureReason.UNKNOWN_EVENT_TYPE);
        assertThat(body.redrivable()).isFalse();
        assertThat(body.eventType()).isEqualTo("SOMETHING_NEW");
    }

    @Test
    void 봉투로_읽을_수_없으면_검색_칸_없이_분류만_남긴다() {
        for (String broken : new String[]{"not-json", "[1,2]", "null", "{\"aggregateId\":\"x\"}"}) {
            DeadLetterBody body = parser.parse(broken);

            assertThat(body.failureReason()).as(broken).isEqualTo(FailureReason.UNREADABLE_BODY);
            assertThat(body.redrivable()).as(broken).isFalse();
            assertThat(body.eventType()).as(broken).isNull();
        }
    }

    @Test
    void 계약_길이를_넘는_검색_칸은_잘라_넣는다() {
        DeadLetterBody body = parser.parse(envelope("X".repeat(80)));

        assertThat(body.eventType()).hasSize(50);
    }

    private String envelope(String eventType) {
        return """
                {"eventId":"0b6f3c9e-2d41-4c8a-9f7e-5a1d2c3b4e5f","eventType":"%s","aggregateType":"PREORDER_SYNC_JOB",
                 "aggregateId":7,"occurredAt":"2026-09-03T01:00:03.470Z","payload":{"preorderId":"9f1c2d3e"}}
                """.formatted(eventType);
    }
}
