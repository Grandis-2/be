package com.grandis.nova.member.adminalert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Grafana 큐 — SQS 가 받은 시각(SentTimestamp) 읽기")
class AdminAlertConsumerConfigTest {

    @Test
    @DisplayName("SentTimestamp(밀리초)를 시각으로 읽는다")
    void readsSentTimestamp() {
        Message message = Message.builder().attributes(Map.of(MessageSystemAttributeName.SENT_TIMESTAMP, "1792990931123")).build();

        assertThat(AdminAlertConsumerConfig.sentAt(message)).isEqualTo(Instant.ofEpochMilli(1792990931123L));
    }

    @Test
    @DisplayName("속성이 없거나 숫자가 아니면 null — Receiver 가 지금 시각을 쓴다")
    void missingOrBrokenIsNull() {
        assertThat(AdminAlertConsumerConfig.sentAt(Message.builder().build())).isNull();
        assertThat(AdminAlertConsumerConfig.sentAt(Message.builder()
                .attributes(Map.of(MessageSystemAttributeName.SENT_TIMESTAMP, "어제")).build())).isNull();
    }
}
