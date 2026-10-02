package com.grandis.nova.catalog.outbox.sqs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * catalog 는 보내기만 한다(받는 큐가 없다). preorder 의 nova.sqs 에서 소비기 · DLQ 칸을 뺀 모양이다.
 *
 * @param region         있을 때만 SQS 클라이언트를 만든다
 * @param endpoint       로컬 에뮬레이터(Floci) 주소. 비우면 AWS 기본 주소와 기본 자격 증명을 쓴다
 * @param queues         논리 목적지 → 실제 큐 이름. 없으면 논리 이름을 그대로 쓴다
 * @param apiCallTimeout SQS 호출 한 번의 제한 시간. 릴레이 리스(nova.outbox.relay-lease)보다 짧아야 한다
 */
@ConfigurationProperties("nova.sqs")
record SqsProperties(
        String region,
        URI endpoint,
        @DefaultValue("test") String accessKey,
        @DefaultValue("test") String secretKey,
        @DefaultValue Map<String, String> queues,
        @DefaultValue("3s") Duration apiCallTimeout
) {

    String queueName(String destination) {
        return queues.getOrDefault(destination, destination);
    }
}
