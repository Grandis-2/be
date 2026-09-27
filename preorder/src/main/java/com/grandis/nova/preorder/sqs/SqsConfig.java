package com.grandis.nova.preorder.sqs;

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

/** nova.sqs.region 이 있을 때만 SQS 클라이언트를 만든다. 없으면 발행은 로그 전송, 소비기는 꺼진다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SqsProperties.class)
class SqsConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "nova.sqs", name = "region")
    SqsClient sqsClient(SqsProperties properties) {
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
}
