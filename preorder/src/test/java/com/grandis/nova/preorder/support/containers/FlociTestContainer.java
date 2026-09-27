package com.grandis.nova.preorder.support.containers;

import io.floci.testcontainers.FlociContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * JVM 에 하나뿐인 Floci(로컬 AWS 에뮬레이터). 시작하면서 큐와 DLQ 를 만든다.
 * 큐 구성은 docker/floci/create-queues.sh 와 같고, DLQ 로 옮기는 횟수만 테스트가 빨리 끝나도록 작게 둔다.
 */
public final class FlociTestContainer {

    public static final String REGION = "ap-northeast-2";
    public static final List<String> QUEUES =
            List.of("preorder-register", "preorder-cancel", "preorder-events", "order-events", "notification");
    public static final int MAX_RECEIVE_COUNT = 2;

    private static final FlociContainer INSTANCE = start();

    private FlociTestContainer() {
    }

    public static FlociContainer get() {
        return INSTANCE;
    }

    private static FlociContainer start() {
        FlociContainer container = new FlociContainer("floci/floci:2.1.0");
        container.start();
        try (SqsClient sqs = SqsClient.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(container.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(container.getAccessKey(), container.getSecretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()) {
            QUEUES.forEach(queue -> createWithDeadLetterQueue(sqs, queue));
        }
        return container;
    }

    private static void createWithDeadLetterQueue(SqsClient sqs, String queue) {
        String dlqUrl = sqs.createQueue(request -> request.queueName(queue + "-dlq")).queueUrl();
        String dlqArn = sqs.getQueueAttributes(request -> request.queueUrl(dlqUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        sqs.createQueue(request -> request.queueName(queue).attributes(Map.of(QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"%d\"}".formatted(dlqArn, MAX_RECEIVE_COUNT))));
    }
}
