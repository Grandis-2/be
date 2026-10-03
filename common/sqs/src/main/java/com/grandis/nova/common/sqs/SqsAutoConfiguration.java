package com.grandis.nova.common.sqs;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * nova.sqs.region 이 있을 때만 SQS 클라이언트 · 큐 URL 을 만든다. 없으면 소비기를 만들 수 없고,
 * nova.outbox.transport=sqs 면 전송이 클라이언트를 찾지 못해 기동이 실패한다.
 *
 * 자동설정이라 서비스의 컴포넌트 스캔(com.grandis.nova)에서는 빠진다. 공통 클래스에는 @Component 를 붙이지 않는다.
 */
@AutoConfiguration
@EnableConfigurationProperties(SqsClientProperties.class)
public class SqsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "nova.sqs", name = "region")
    SqsClient sqsClient(SqsClientProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(properties.apiCallTimeout()));
        if (properties.endpoint() == null) {
            return builder.credentialsProvider(DefaultCredentialsProvider.builder().build()).build();
        }
        return builder.endpointOverride(properties.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "nova.sqs", name = "region")
    SqsQueueUrls sqsQueueUrls(SqsClient sqs, SqsClientProperties properties) {
        return new SqsQueueUrls(sqs, properties);
    }

    /**
     * 아웃박스 SQS 전송. common:outbox 가 클래스패스에 있을 때만 읽힌다 — 조건을 클래스 이름 문자열로 걸어,
     * 아웃박스 없이 SQS 만 쓰는 서비스에서 아웃박스 형을 읽지 않는다. 리스 길이와의 관계는 common:outbox 가 기동할 때 본다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.grandis.nova.common.outbox.MessageTransport")
    static class OutboxTransport {

        @Bean
        @ConditionalOnMissingBean(type = "com.grandis.nova.common.outbox.MessageTransport")
        @ConditionalOnProperty(name = "nova.outbox.transport", havingValue = "sqs")
        SqsMessageTransport sqsMessageTransport(SqsClient sqs, SqsQueueUrls queueUrls, SqsClientProperties properties) {
            return new SqsMessageTransport(sqs, queueUrls, properties.apiCallTimeout());
        }
    }
}
