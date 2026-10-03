package com.grandis.nova.common.sqs;

import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 논리 목적지의 큐 URL. 처음 한 번만 묻고 기억한다. 실제 큐 이름은 nova.sqs.queues 로 바꿀 수 있다.
 * 묻는 동안 맵을 잠그지 않는다 — 큐 장애 중에 같은 목적지를 보내는 스레드들이 서로를 기다리면 한 건의 전송이
 * 호출 두 번(조회 + 전송)의 제한 시간을 넘긴다. 처음에 몇 번 겹쳐 물어도 결과는 같다.
 */
public final class SqsQueueUrls {

    private final SqsClient sqs;
    private final SqsClientProperties properties;
    private final Map<String, String> urls = new ConcurrentHashMap<>();

    SqsQueueUrls(SqsClient sqs, SqsClientProperties properties) {
        this.sqs = sqs;
        this.properties = properties;
    }

    public String of(String destination) {
        String url = urls.get(destination);
        if (url != null) {
            return url;
        }
        String resolved = sqs.getQueueUrl(request -> request.queueName(properties.queueName(destination))).queueUrl();
        String previous = urls.putIfAbsent(destination, resolved);
        return previous != null ? previous : resolved;
    }
}
