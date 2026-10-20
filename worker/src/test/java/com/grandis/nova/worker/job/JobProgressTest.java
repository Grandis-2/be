package com.grandis.nova.worker.job;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobProgressTest {

    @Test
    void 속성에서_실행_회차_차례_세대_직전_결과_시작한_쪽을_읽는다() {
        JobProgress progress = JobProgress.of(message(Map.of(
                "run", "4", "attempt", "3", "generation", "2", "lastOutcome", "UNKNOWN", "actor", "ADMIN")));

        assertThat(progress).isEqualTo(new JobProgress(4, 3, 2, AttemptResult.UNKNOWN, Actor.ADMIN));
    }

    @Test
    void 속성이_없으면_처음으로_본다() {
        assertThat(JobProgress.of(message(Map.of()))).isEqualTo(new JobProgress(1, 1, 1, null, Actor.SYSTEM));
    }

    @Test
    void 읽을_수_없는_속성은_버리지_않고_처음으로_본다() {
        JobProgress progress = JobProgress.of(message(Map.of(
                "run", "-", "attempt", "x", "generation", "0", "lastOutcome", "???", "actor", "ROOT")));

        assertThat(progress).isEqualTo(new JobProgress(1, 1, 1, null, Actor.SYSTEM));
    }

    @Test
    void 지연_재전송에_넘길_속성으로_바꾸고_직전_결과가_없으면_그_속성을_뺀다() {
        assertThat(new JobProgress(3, 2, 1, AttemptResult.TRANSIENT_FAILURE, Actor.SYSTEM).toAttributes())
                .containsOnly(Map.entry("run", "3"), Map.entry("attempt", "2"), Map.entry("generation", "1"),
                        Map.entry("lastOutcome", "TRANSIENT_FAILURE"), Map.entry("actor", "SYSTEM"),
                        new SimpleEntry<>("misconfiguredSince", null));

        Map<String, String> first = new JobProgress(1, 1, 2, null, Actor.ADMIN).toAttributes();
        assertThat(first).containsEntry("lastOutcome", null).containsEntry("actor", "ADMIN");
    }

    @Test
    void 소비기가_받아_올_속성에_진행_속성이_모두_들어_있다() {
        assertThat(JobProgress.ATTRIBUTE_NAMES)
                .containsAll(new JobProgress(1, 1, 1, AttemptResult.UNKNOWN, Actor.SYSTEM).toAttributes().keySet())
                .contains("eventType", "eventId");
    }

    @Test
    void 차례_세대는_1부터다() {
        assertThatThrownBy(() -> new JobProgress(0, 1, 1, null, Actor.SYSTEM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobProgress(1, 0, 1, null, Actor.SYSTEM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobProgress(1, 1, 0, null, Actor.SYSTEM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobProgress(1, 1, 1, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Message message(Map<String, String> attributes) {
        Map<String, MessageAttributeValue> values = new HashMap<>();
        attributes.forEach((name, value) ->
                values.put(name, MessageAttributeValue.builder().dataType("String").stringValue(value).build()));
        return Message.builder().messageId("m-1").body("{}").messageAttributes(values).build();
    }
}
