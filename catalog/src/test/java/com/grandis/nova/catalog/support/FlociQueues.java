package com.grandis.nova.catalog.support;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.util.List;

/**
 * JVM 에 하나뿐인 Floci(로컬 AWS 에뮬레이터). catalog 가 보내는 두 큐(preorder-events · order-events)를 만든다.
 * preorder · order 시험의 FlociTestContainer 와 같은 이미지다. catalog 는 받지 않으므로 DLQ 는 만들지 않는다.
 */
public final class FlociQueues {

    public static final String REGION = "ap-northeast-2";
    public static final List<String> QUEUES = List.of("preorder-events", "order-events");

    private static final io.floci.testcontainers.FlociContainer INSTANCE = start();

    private FlociQueues() {
    }

    public static io.floci.testcontainers.FlociContainer get() {
        return INSTANCE;
    }

    public static SqsClient client() {
        return SqsClient.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(INSTANCE.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(INSTANCE.getAccessKey(), INSTANCE.getSecretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static io.floci.testcontainers.FlociContainer start() {
        io.floci.testcontainers.FlociContainer container = new io.floci.testcontainers.FlociContainer("floci/floci:2.1.0");
        container.start();
        try (SqsClient sqs = SqsClient.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(container.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(container.getAccessKey(), container.getSecretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()) {
            QUEUES.forEach(queue -> sqs.createQueue(request -> request.queueName(queue)));
        }
        return container;
    }
}
