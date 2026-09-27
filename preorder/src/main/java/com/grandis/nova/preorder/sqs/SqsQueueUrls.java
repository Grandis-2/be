package com.grandis.nova.preorder.sqs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 논리 목적지의 큐 URL. 처음 한 번만 묻고 기억한다. */
@Component
@ConditionalOnProperty(prefix = "nova.sqs", name = "region")
class SqsQueueUrls {

    private final SqsClient sqs;
    private final SqsProperties properties;
    private final Map<String, String> urls = new ConcurrentHashMap<>();

    SqsQueueUrls(SqsClient sqs, SqsProperties properties) {
        this.sqs = sqs;
        this.properties = properties;
    }

    String of(String destination) {
        return urls.computeIfAbsent(destination,
                name -> sqs.getQueueUrl(request -> request.queueName(properties.queueName(name))).queueUrl());
    }
}
