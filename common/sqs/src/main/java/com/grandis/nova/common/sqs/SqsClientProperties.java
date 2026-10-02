package com.grandis.nova.common.sqs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * SQS 클라이언트 설정. 소비 설정(nova.sqs.consumer 등)은 큐마다 다르므로 서비스가 따로 받는다.
 *
 * @param endpoint       로컬 에뮬레이터(Floci) 주소 — 로컬 · 시험 전용이다. 정하면 accessKey · secretKey 로 정적 자격 증명을 쓰고
 *                       (기본값은 에뮬레이터의 test), 비우면 AWS 기본 주소와 기본 자격 증명(IAM 역할)을 쓴다.
 *                       배포 환경은 endpoint 를 비운다(설정 예시와 같다)
 * @param queues         논리 목적지 → 실제 큐 이름. 없으면 논리 이름을 그대로 쓴다
 * @param apiCallTimeout SQS 호출 한 번의 제한 시간. 아웃박스 전송 한 번은 최대 두 번 부르므로 그 두 배가 릴레이 리스보다 짧아야 한다
 */
@ConfigurationProperties("nova.sqs")
record SqsClientProperties(
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
